package com.aaa.notifier.telegram;

import com.aaa.notifier.filter.AlertDecisionSink;
import com.aaa.notifier.filter.FilterMetrics;
import java.time.Clock;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 디스패처·이관 지점·요약이 함께 쓰는 협력자 묶음 (매개변수 객체).
 *
 * <p>협력자는 모두 스레드 안전한 공유 서비스라 방어적 복사를 하지 않는다 — 같은 대기 큐·safe_mode 키를 모든 경로가 봐야 한다.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public final class DeliveryContext {

    /** Bot API. */
    private final TelegramApi api;

    /** safe_mode 키. */
    private final SafeModeStore safeMode;

    /** 대기 큐. */
    private final PendingQueue queue;

    /** {@code notification_log} 발송 사건 기록. */
    private final NotificationLog notificationLog;

    /** {@code stream:alert} 발행. */
    private final AlertPublisher alertPublisher;

    /** 운영자 kill switch 동안 발송 후보를 기록하는 FILTER-001 DRYRUN 싱크 (design.md §4.3). */
    private final AlertDecisionSink dryRunSink;

    /** 메시지 본문. */
    private final TelegramMessageFormatter formatter;

    /** 실발송 계측. */
    private final TelegramMetrics metrics;

    /** FILTER-001 결정 계측 — 발송 후보는 최종 처리가 정해질 때 한 번만 올린다. */
    private final FilterMetrics filterMetrics;

    /** 시계 (KST). */
    private final Clock clock;

    /** 대기. */
    private final Sleeper sleeper;

    /** 발송 정책 수치. */
    private final DeliveryPolicy policy;
}
