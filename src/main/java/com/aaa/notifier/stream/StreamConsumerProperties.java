package com.aaa.notifier.stream;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 소비 루프 설정 (REQ-NOTIFIER-CONSUMER-012 — 전부 외부화).
 *
 * <p>여섯 값이 전부 {@code application.yml}에서 조정 가능해야 한다 — 소스에 박아두면 장중에 블로킹 대기나 DLQ 임계를 바꾸려 할 때 재배포가
 * 필요해진다. 일곱 번째 {@code dlqMaxLen}은 REQ가 아니라 TECHSPEC §5.1(스트림별 MAXLEN 환경 변수 외부화)을 따른 값이다.
 *
 * <p>재소유 스캔 1회 건수는 여기에 두지 않았다. 운영 중 조정할 이유가 없는 내부 페이지 크기이고(analyzer {@code consumer.py}의 {@code
 * _CLAIM_BATCH_SIZE}와 동일 취지), 설정 표면을 늘리면 실제로 조정해야 하는 여섯 값이 묻힌다.
 *
 * @param enabled 소비 활성화 플래그. {@code false}면 그룹 생성도 소비 스레드 기동도 하지 않는다(기동 자체는 성공한다)
 * @param blockTimeout {@code XREADGROUP} 블로킹 대기 시간. 종료 신호에 대한 반응 지연의 상한이기도 하다
 * @param readBatchSize {@code XREADGROUP} 1회 읽기 건수
 * @param claimIdleThreshold 재소유 유휴 임계 — 이 시간을 넘게 미확인인 메시지가 재소유 대상이다
 * @param maxDeliveryCount DLQ 재전달 임계. 이 값을 <b>초과</b>하면 이관한다(기본 3 → 4회째가 이관 대상)
 * @param errorBackoff 일시적 오류 후 재시도까지의 대기 시간
 * @param dlqMaxLen DLQ({@code stream:dlq:{stream명}}) 길이 상한. 초과분은 가장 오래된 항목부터 <b>정확 트리밍</b>으로 밀려난다
 *     (TECHSPEC §5.1)
 */
@ConfigurationProperties(prefix = "notifier.stream")
public record StreamConsumerProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("2s") Duration blockTimeout,
        @DefaultValue("10") int readBatchSize,
        @DefaultValue("60s") Duration claimIdleThreshold,
        @DefaultValue("3") int maxDeliveryCount,
        @DefaultValue("5s") Duration errorBackoff,
        @DefaultValue("500") long dlqMaxLen) {}
