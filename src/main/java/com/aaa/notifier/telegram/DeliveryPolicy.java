package com.aaa.notifier.telegram;

import java.time.Duration;

/**
 * 발송 정책 수치 (plan.md §F) — {@link TelegramProperties}에서 디스패처가 쓰는 값만 모은다.
 *
 * @param minSendInterval 같은 chat_id 연속 요청 최소 간격
 * @param transientRetries 일시 오류 재시도 K
 * @param backoffInitial 첫 재시도 전 대기
 * @param backoffMultiplier 재시도마다 곱하는 배수
 * @param rateLimitDefaultWait 429에 {@code retry_after}가 없을 때의 대기
 * @param rateLimitMaxCumulativeWait 알림 1건당 429 누적 대기 상한(이번 대기 포함)
 * @param rateLimitMaxResends 알림 1건당 429 재발송 횟수 상한
 * @param failureThreshold safe_mode 자동 진입 임계 N
 * @param retention 대기 큐 보존 기간
 * @param summaryListLimit 요약 목록 상한
 */
public record DeliveryPolicy(
        Duration minSendInterval,
        int transientRetries,
        Duration backoffInitial,
        int backoffMultiplier,
        Duration rateLimitDefaultWait,
        Duration rateLimitMaxCumulativeWait,
        int rateLimitMaxResends,
        int failureThreshold,
        Duration retention,
        int summaryListLimit) {

    /**
     * @param properties 실발송 설정
     * @return 발송 정책
     */
    public static DeliveryPolicy from(TelegramProperties properties) {
        return new DeliveryPolicy(
                properties.minSendInterval(),
                properties.retry().transientRetries(),
                properties.retry().backoffInitial(),
                properties.retry().backoffMultiplier(),
                properties.rateLimit().defaultWait(),
                properties.rateLimit().maxCumulativeWait(),
                properties.rateLimit().maxResends(),
                properties.safeMode().failureThreshold(),
                properties.queue().retention(),
                properties.queue().summaryListLimit());
    }
}
