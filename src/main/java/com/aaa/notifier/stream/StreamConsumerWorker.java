package com.aaa.notifier.stream;

import com.aaa.notifier.common.logging.TraceIdManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 스트림 1개를 소비하는 수동 폴링 루프 (REQ-NOTIFIER-CONSUMER-003/011/012/013/014).
 *
 * <p>Spring의 {@code StreamMessageListenerContainer}를 쓰지 않는다. 컨테이너는 {@code XREADGROUP}만 제공하고 {@code
 * XPENDING}으로 재전달 횟수를 판독하는 훅이 없는데, REQ-011(재소유 순서)과 REQ-021 ①(재전달 임계 DLQ)이 둘 다 그 판독을 전제한다 (spec.md
 * HISTORY [D-C1]).
 *
 * <p><b>회차 순서: 판독 → 재소유 → 신규 읽기.</b> 재할당 명령은 그 자체가 재전달 횟수를 1 증가시키므로, 판독을 재할당 뒤에 두면 재전달 3회 메시지가 4회로
 * 보여 DLQ 임계 판정이 한 회차씩 밀린다(analyzer {@code consumer.py}가 이미 문서화하고 회피한 함정).
 *
 * <p><b>재할당은 {@code XCLAIM}으로 한다 — {@code XAUTOCLAIM}이 아니다.</b> Spring Data Redis 3.5의 {@code
 * StreamOperations}는 {@code XAUTOCLAIM}을 노출하지 않는다(실측 확인: {@code claim}/{@code pending}만 존재). 이미
 * {@code XPENDING}으로 대상 ID와 재전달 횟수를 알고 있으므로 명시적 ID {@code XCLAIM}이 의미론적으로 동등하며, 임계를 넘긴 메시지는 아예
 * 재소유하지 않아 불필요한 재전달 횟수 증가도 피한다. 순서 보장(판독 선행)은 그대로다.
 *
 * <p>{@code @Scheduled(fixedDelay=...)}로 구현하지 않는다(ADR-008 + REQ-012 후단). 블로킹 {@code XREADGROUP} 루프는
 * {@code @Scheduled} 잡이 아니므로 Virtual Threads 스케줄러 결함의 적용 대상이 아니다.
 *
 * <p>@MX:WARN: [AUTO] 전용 가상 스레드에서 무한 루프로 돌며 블로킹 Redis 호출을 수행한다. 종료 신호는 {@link #stop()}의 플래그와 스레드
 * 인터럽트 두 경로로 도달하며, 두 경로 모두 처리하지 않으면 컨텍스트 종료가 걸린다.
 *
 * <p>@MX:REASON: 스레드 생명주기를 직접 관리하는 무한 루프 + 인터럽트 처리 분기.
 */
@Slf4j
public class StreamConsumerWorker implements Runnable {

    /**
     * 재소유 스캔 1회에 검사할 미확인 메시지 수. 운영 중 조정할 이유가 없는 내부 페이지 크기이므로 설정으로 외부화하지 않는다(analyzer {@code
     * consumer.py} {@code _CLAIM_BATCH_SIZE}와 동일 취지·동일 값).
     */
    private static final int RECLAIM_SCAN_SIZE = 100;

    private final ConsumedStream stream;

    /**
     * 템플릿이 아니라 스트림 연산 뷰를 보관한다. {@code opsForStream()}은 템플릿에 묶인 무상태 뷰라 한 번 얻어 재사용해도 안전하고, 가변 템플릿 참조를
     * 필드로 들고 있지 않게 되어 정적 분석의 내부 표현 노출 경고도 사라진다.
     */
    private final StreamOperations<String, String, String> streamOperations;

    private final StreamConsumerProperties properties;
    private final TickPayloadParser tickParser;
    private final SignalPayloadParser signalParser;
    private final StreamObservationHandler handler;
    private final DeadLetterPublisher deadLetterPublisher;

    private final AtomicBoolean running = new AtomicBoolean(true);

    public StreamConsumerWorker(
            ConsumedStream stream,
            StringRedisTemplate redisTemplate,
            StreamConsumerProperties properties,
            TickPayloadParser tickParser,
            SignalPayloadParser signalParser,
            StreamObservationHandler handler,
            DeadLetterPublisher deadLetterPublisher) {
        this.stream = stream;
        this.streamOperations = redisTemplate.opsForStream();
        this.properties = properties;
        this.tickParser = tickParser;
        this.signalParser = signalParser;
        this.handler = handler;
        this.deadLetterPublisher = deadLetterPublisher;
    }

    /**
     * 종료 신호가 올 때까지 회차를 반복한다.
     *
     * <p>일시적 오류(Redis 두절 등)는 백오프 후 재시도하며 프로세스를 종료하지 않는다(REQ-014) — 한 스트림의 실패가 나머지 3개의 소비를 중단시키지 않아야
     * 하고(REQ-003), 이 워커가 죽으면 그 스트림만 조용히 멈춘다.
     */
    @Override
    public void run() {
        // 재기동 대비 — 러너는 워커 인스턴스를 재사용하므로, 여기서 되살리지 않으면 이전 stop()의 플래그가 남아
        // 재기동 후 루프가 한 바퀴도 돌지 않고 조용히 끝난다(통합 테스트 재기동 시나리오에서 실측으로 드러났다).
        running.set(true);
        log.info(
                "[stream-consumer] 소비 시작 stream={} group={} consumer={}",
                stream.getKey(),
                ConsumedStream.CONSUMER_GROUP,
                stream.getConsumerName());

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                pollOnce();
            } catch (DataAccessException e) {
                // 회차 자체가 실패하는 경로는 Redis 호출뿐이다 — 메시지 단위 실패는 handleRecord가 이미 흡수한다.
                // 따라서 여기서 잡아야 할 것은 Spring이 번역한 Redis 예외이며, 그 밖의 예외는 실제 결함이므로
                // 조용히 무한 재시도하지 않고 스레드를 끝내 드러낸다.
                if (isShuttingDown()) {
                    break;
                }
                log.error("[stream-consumer] 회차 실패 — 백오프 후 재시도한다 stream={}", stream.getKey(), e);
                if (!backoff()) {
                    break;
                }
            }
        }
        log.info("[stream-consumer] 소비 종료 stream={}", stream.getKey());
    }

    /** 루프 탈출을 요청한다. 진행 중인 블로킹 읽기는 {@code blockTimeout} 안에 반환되거나 인터럽트로 깨어난다. */
    public void stop() {
        running.set(false);
    }

    /**
     * 1회차를 수행한다 — 판독 → 재소유 → 신규 읽기.
     *
     * @return 하류로 전달되어 확인응답까지 마친 메시지 수
     */
    public int pollOnce() {
        return reclaimStale() + readNew();
    }

    /**
     * 유휴 임계를 넘긴 미확인 메시지를 처리한다.
     *
     * <p>재전달 횟수를 <b>먼저</b> 판독해 임계 초과분을 가려낸 뒤, 나머지만 재소유한다. 초과분은 재소유하지 않고 바로 DLQ로 보낸다 — 재소유는 재전달 횟수를
     * 또 늘릴 뿐 어차피 이관될 메시지다.
     */
    private int reclaimStale() {
        Duration idleThreshold = properties.claimIdleThreshold();
        PendingMessages pending =
                streamOperations.pending(
                        stream.getKey(),
                        ConsumedStream.CONSUMER_GROUP,
                        Range.unbounded(),
                        RECLAIM_SCAN_SIZE);

        List<RecordId> reclaimable = new ArrayList<>();
        List<String> overLimit = new ArrayList<>();
        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().compareTo(idleThreshold) < 0) {
                continue;
            }
            if (message.getTotalDeliveryCount() > properties.maxDeliveryCount()) {
                overLimit.add(message.getIdAsString());
            } else {
                reclaimable.add(message.getId());
            }
        }

        overLimit.forEach(this::deadLetterOverLimit);
        if (reclaimable.isEmpty()) {
            return 0;
        }

        List<MapRecord<String, String, String>> claimed =
                streamOperations.claim(
                        stream.getKey(),
                        ConsumedStream.CONSUMER_GROUP,
                        stream.getConsumerName(),
                        XClaimOptions.minIdle(idleThreshold).ids(reclaimable));
        return handleAll(claimed);
    }

    /** 임계를 넘긴 메시지를 DLQ로 보낸다. 엔트리 본문은 PEL에 없으므로 {@code XRANGE}로 읽는다(재전달 횟수를 늘리지 않는 읽기다). */
    private void deadLetterOverLimit(String messageId) {
        List<MapRecord<String, String, String>> found =
                streamOperations.range(stream.getKey(), Range.closed(messageId, messageId));

        // 엔트리가 이미 MAXLEN 트리밍으로 사라졌을 수 있다 — 그때는 남은 메타데이터만 실어 보낸다.
        // (연결 계층이 null을 돌려주는 경로도 같은 취급이다.)
        Map<String, String> fields =
                found == null || found.isEmpty() ? Map.of() : found.getFirst().getValue();
        deadLetterPublisher.transfer(
                stream, messageId, fields, DeadLetterPublisher.Reason.MAX_DELIVERY_EXCEEDED);
    }

    private int readNew() {
        List<MapRecord<String, String, String>> records =
                streamOperations.read(
                        Consumer.from(ConsumedStream.CONSUMER_GROUP, stream.getConsumerName()),
                        StreamReadOptions.empty()
                                .count(properties.readBatchSize())
                                .block(properties.blockTimeout()),
                        StreamOffset.create(stream.getKey(), ReadOffset.lastConsumed()));
        return records == null ? 0 : handleAll(records);
    }

    private int handleAll(List<MapRecord<String, String, String>> records) {
        int handled = 0;
        for (MapRecord<String, String, String> record : records) {
            if (handleRecord(record)) {
                handled++;
            }
        }
        return handled;
    }

    /**
     * 메시지 1건을 처리한다.
     *
     * <p>하류 전달의 성패만 격리 경계({@link #attemptDelivery})가 판정하고, 확인응답·DLQ 이관은 그 바깥에서 수행한다. 그래서 확인응답이나 DLQ
     * 기록이 Redis 오류로 실패하면 "하류 실패"로 오인되어 삼켜지지 않고 {@link #run()}의 백오프 경로로 전파된다.
     *
     * @return 하류 전달이 성공해 확인응답까지 마쳤으면 {@code true}
     */
    private boolean handleRecord(MapRecord<String, String, String> record) {
        String messageId = record.getId().getValue();
        Map<String, String> fields = record.getValue();

        // 발행 측 trace_id를 승계해 발행→소비를 하나의 추적 ID로 관통시킨다(REQ-031).
        TraceIdManager.set(fields.get(TickPayloadParser.FIELD_TRACE_ID));
        try {
            Optional<Throwable> failure = attemptDelivery(fields);
            if (failure.isEmpty()) {
                acknowledge(messageId);
                return true;
            }
            if (failure.get() instanceof PayloadUnparseableException unparseable) {
                // 재시도해도 유효해지지 않는다 — 임계를 기다리지 않고 즉시 이관한다(REQ-021 ②).
                log.warn(
                        "[stream-consumer] 페이로드 해석 불가 — 즉시 DLQ로 이관한다 stream={} id={} reason={}",
                        stream.getKey(),
                        messageId,
                        unparseable.getMessage());
                deadLetterPublisher.transfer(
                        stream, messageId, fields, DeadLetterPublisher.Reason.PAYLOAD_UNPARSEABLE);
                return false;
            }
            // 확인응답하지 않는다 — 미확인으로 남아 다음 회차의 재소유 대상이 된다(REQ-013).
            log.error(
                    "[stream-consumer] 하류 전달 실패 — 확인응답하지 않는다 stream={} id={}",
                    stream.getKey(),
                    messageId,
                    failure.get());
            return false;
        } finally {
            TraceIdManager.clear();
        }
    }

    /**
     * 하류 전달을 격리 실행하고, 예외로 끝났으면 그 원인을 돌려준다(정상이면 빈 값). 어떤 예외도 호출자에게 전파하지 않는다.
     *
     * <p><b>왜 {@code catch (Exception)}이 아닌가.</b> 하류 핸들러는 외부 구현체(FILTER-001)라 던질 예외의 종류를 알 수 없다.
     * 그런데 AC-3은 "핸들러가 호출마다 예외를 던져도 어떤 소비 단위도 종료되지 않을 것"을, AC-7은 "그 경우 확인응답하지 않을 것"을 함께 요구한다. 포괄
     * catch로 막으면 PMD {@code AvoidCatchingGenericException}에 걸리는데, 정적 분석 예외는 추가하지 않기로 했다. 대신 실행 결과를
     * {@link CompletableFuture}에 담아 <em>결과 값</em>으로 수거한다 — 실패는 catch 절이 아니라 완료 상태로 관측된다.
     *
     * <p>실행기로 {@code Runnable::run}을 주므로 <b>호출 스레드에서 동기 실행</b>된다. 스레드를 건너뛰지 않으니 {@code
     * TraceIdManager}의 MDC(스레드 로컬)와 인터럽트 상태가 그대로 유지되고, 확인응답 이전에 전달이 끝나야 한다는 순서(REQ-051)도 깨지지 않는다.
     *
     * <p>{@link Error}는 격리하지 않고 그대로 던진다. {@code CompletableFuture}는 {@code Throwable} 전체를 삼키는데,
     * {@code OutOfMemoryError} 같은 VM 오류를 "하류 실패"로 취급해 루프를 계속 돌리면 결함이 가려진다. 기존 {@code catch
     * (Exception)}도 Error는 통과시켰으므로 그 동작을 유지한다.
     *
     * <p>@MX:WARN: [AUTO] catch 절이 없고 실행기가 {@code Runnable::run}인 구조가 의도다. 다른 실행기로 바꾸면 전달이 소비 스레드를
     * 벗어난다.
     *
     * <p>@MX:REASON: 호출 스레드 동기 실행이라야 {@code TraceIdManager}의 MDC(스레드 로컬)와 인터럽트 상태가 유지되고 하류 전달이
     * 확인응답보다 먼저 끝난다(REQ-051). 비동기 실행기로 바꾸면 이 두 가지가 조용히 깨진다.
     */
    private Optional<Throwable> attemptDelivery(Map<String, String> fields) {
        CompletableFuture<Void> delivery =
                CompletableFuture.runAsync(() -> deliver(fields), Runnable::run);
        if (!delivery.isCompletedExceptionally()) {
            return Optional.empty();
        }
        Throwable cause = delivery.exceptionNow();
        if (cause instanceof Error error) {
            throw error;
        }
        return Optional.of(cause);
    }

    /** 관측치를 하류 포트로 <b>동기</b> 전달한다 — 확인응답 이전이어야 at-least-once가 성립한다(REQ-051). */
    private void deliver(Map<String, String> fields) {
        if (stream.getPayloadKind() == ConsumedStream.PayloadKind.SIGNAL) {
            handler.onSignal(signalParser.parse(fields));
            return;
        }
        for (TickObservation observation : tickParser.parse(stream, fields)) {
            switch (observation) {
                case TradeTickObservation trade -> handler.onTradeTick(trade);
                case QuoteObservation quote -> handler.onQuote(quote);
            }
        }
    }

    private void acknowledge(String messageId) {
        streamOperations.acknowledge(stream.getKey(), ConsumedStream.CONSUMER_GROUP, messageId);
    }

    private boolean isShuttingDown() {
        return !running.get() || Thread.currentThread().isInterrupted();
    }

    /**
     * 백오프 대기.
     *
     * @return 대기를 마치고 루프를 계속해도 되면 {@code true}, 인터럽트로 종료해야 하면 {@code false}
     */
    private boolean backoff() {
        try {
            Thread.sleep(properties.errorBackoff().toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
