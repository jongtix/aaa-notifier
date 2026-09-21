package com.aaa.notifier.stream;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 유독 메시지를 {@code stream:dlq:{원본 스트림명}}으로 이관한다 (REQ-NOTIFIER-CONSUMER-021/022).
 *
 * <p>두 트리거가 있다 — 재전달 한도 초과({@link Reason#MAX_DELIVERY_EXCEEDED})와 페이로드 해석 불가({@link
 * Reason#PAYLOAD_UNPARSEABLE}). 후자는 재시도해도 유효해지지 않으므로 재전달 임계를 기다리지 않고 첫 회차에 즉시 이관한다.
 *
 * <p><b>이관 순서가 계약이다: DLQ XADD가 먼저, 원본 XACK가 나중.</b> 반대로 하면 XADD가 실패했을 때 원본은 이미 확인응답되어 메시지가 DLQ에도 원본
 * PEL에도 남지 않는다 — 조용한 소실이다. 이 순서는 analyzer {@code consumer.py} {@code _route_to_dlq()}와 동일하다.
 *
 * <p><b>DLQ 길이는 상한이 있다</b>(TECHSPEC §5.1 — {@code stream:dlq:{stream명}} MAXLEN 500, <b>정확</b> 트리밍).
 * 상한을 넘기면 가장 오래된 항목부터 밀려난다. 근사({@code ~}) 트리밍이 아니라는 점이 요점이다 — 근사 트리밍은 매크로 노드 단위로만 잘라 상한을 넘겨 보존할 수
 * 있다.
 *
 * <p>이관 사실은 WARN 구조화 로그로만 남기고 <b>텔레그램 등 알림 채널로 직접 발송하지 않는다</b>(REQ-022). 사람에게 도달하는 경로(메트릭 + vmalert
 * 룰)는 OBSV-001 소관이며, FOUNDATION-001이 확립한 "시스템 알림은 vmalert/CD 경로로 일원화" 방침과 정합한다.
 *
 * <p><b>{@code @Component}가 아니다.</b> 이 빈은 {@code StreamConsumerAutoConfiguration}이 {@code
 * StringRedisTemplate} 존재를 조건으로 등록한다 — 컴포넌트 스캔 대상으로 두면 RedisAutoConfiguration을 제외한 컨텍스트(smoke
 * 프로파일)에서 의존성 해소 실패로 기동 자체가 깨진다.
 */
@Slf4j
@RequiredArgsConstructor
public class DeadLetterPublisher {

    /** 이관 페이로드에 부가되는 원본 메시지 ID 키. */
    public static final String FIELD_ORIGINAL_ID = "original_id";

    /** 이관 페이로드에 부가되는 원본 스트림명 키. */
    public static final String FIELD_ORIGINAL_STREAM = "original_stream";

    /** 이관 페이로드에 부가되는 이관 사유 키. */
    public static final String FIELD_REASON = "reason";

    private final StringRedisTemplate redisTemplate;

    /** DLQ 길이 상한 — {@code notifier.stream.dlq-max-len}(기본 500). */
    private final long dlqMaxLen;

    /** DLQ 이관 사유 (REQ-021의 두 트리거). */
    @Getter
    public enum Reason {
        /** 재전달 횟수가 설정 임계를 초과했다. */
        MAX_DELIVERY_EXCEEDED("max_delivery_exceeded"),

        /** 페이로드가 구조적으로 해석 불가하다 — 재시도 없이 즉시 이관한다. */
        PAYLOAD_UNPARSEABLE("payload_unparseable");

        /** 이관 페이로드 {@code reason} 필드에 실리는 코드. */
        private final String code;

        Reason(String code) {
            this.code = code;
        }
    }

    /**
     * 메시지 1건을 DLQ로 이관한 뒤 원본에서 확인응답한다.
     *
     * @param stream 원본 스트림
     * @param originalId 원본 메시지 ID
     * @param originalFields 원본 엔트리 필드 (전부 보존된다; 엔트리가 이미 트리밍됐다면 비어 있을 수 있다)
     * @param reason 이관 사유
     */
    public void transfer(
            ConsumedStream stream,
            String originalId,
            Map<String, String> originalFields,
            Reason reason) {
        Map<String, String> payload =
                Stream.concat(
                                originalFields.entrySet().stream(),
                                Stream.of(
                                        Map.entry(FIELD_ORIGINAL_ID, originalId),
                                        Map.entry(FIELD_ORIGINAL_STREAM, stream.getKey()),
                                        Map.entry(FIELD_REASON, reason.getCode())))
                        // 부가 필드가 원본 필드와 같은 이름이면 부가 필드가 이긴다 — 이관 사유를 원본이 덮어쓰면 진단이 무의미해진다.
                        .collect(
                                Collectors.toMap(
                                        Map.Entry::getKey,
                                        Map.Entry::getValue,
                                        (original, provenance) -> provenance));

        // XADD가 먼저다 — 역순이면 XADD 실패 시 메시지가 어디에도 남지 않는다.
        // 상한은 XADD와 같은 명령의 MAXLEN으로 건다(approximateTrimming 미지정 = 정확 트리밍).
        redisTemplate
                .<String, String>opsForStream()
                .add(MapRecord.create(stream.dlqKey(), payload), XAddOptions.maxlen(dlqMaxLen));
        redisTemplate
                .<String, String>opsForStream()
                .acknowledge(stream.getKey(), ConsumedStream.CONSUMER_GROUP, originalId);

        log.warn(
                "[stream-dlq] 메시지를 DLQ로 이관했다 stream={} dlq={} original_id={} reason={} trace_id={}",
                stream.getKey(),
                stream.dlqKey(),
                originalId,
                reason.getCode(),
                originalFields.get(TickPayloadParser.FIELD_TRACE_ID));
    }
}
