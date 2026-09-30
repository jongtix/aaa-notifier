package com.aaa.notifier.telegram;

import com.aaa.notifier.common.logging.TraceIdManager;
import com.aaa.notifier.filter.MarketSession;
import com.aaa.notifier.telegram.SendLoop.Attempt;
import com.aaa.notifier.telegram.TelegramMetrics.Channel;
import com.aaa.notifier.telegram.TelegramMetrics.DiscardReason;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;

/**
 * 대기 큐 요약 발송 (REQ-033, design.md §5) — 디스패처 스레드에서만 돈다.
 *
 * <p>요약 1건은 여러 알림을 묶으므로 원본 {@code trace_id} 하나를 물려받을 수 없다 — 시도마다 새 {@code trace_id}를 발급해 그 시도의 행에
 * 쓰고, 요약과 원본을 잇는 경로는 시도마다 남기는 구조화 로그 1건이다(plan.md §D.12). 영구 오류(4xx)면 포함 항목을 폐기해 같은 요약을 반복하지
 * 않는다(plan.md §D.13). 요약 결과는 연속 실패 카운터에 반영하지 않는다.
 */
@Slf4j
final class QueueSummarizer {

    private final DeliveryContext context;
    private final SendLoop sendLoop;

    QueueSummarizer(DeliveryContext context, SendLoop sendLoop) {
        this.context = context;
        this.sendLoop = sendLoop;
    }

    /**
     * 요약 요청 1건을 처리한다. safe_mode가 꺼져 있지 않으면 아무것도 하지 않는다(다음 계기에 다시).
     *
     * @throws InterruptedException 대기 중 정상 종료 인터럽트 — 큐는 그대로 남는다
     */
    void summarize() throws InterruptedException {
        if (context.safeMode().read().state() != SafeModeState.OFF) {
            return;
        }
        String summaryTraceId = TraceIdManager.generate();
        try {
            QueueSnapshot snapshot = context.queue().snapshot();
            if (snapshot.rawCount() > 0) {
                summarize(summaryTraceId, snapshot);
            }
        } catch (DataAccessException e) {
            log.warn(
                    "[telegram-summary] 대기 큐 접근 실패 — 다음 계기에 다시 시도 summary_trace_id={} error={}",
                    summaryTraceId,
                    e.getMessage());
        } finally {
            TraceIdManager.clear();
        }
    }

    private void summarize(String summaryTraceId, QueueSnapshot snapshot)
            throws InterruptedException {
        LocalDateTime now = LocalDateTime.now(context.clock().withZone(MarketSession.KST));
        LocalDateTime cutoff = now.minus(context.policy().retention());
        List<QueuedAlert> included = new ArrayList<>();
        List<QueuedAlert> expired = new ArrayList<>();
        for (QueuedAlert alert : snapshot.alerts()) {
            if (alert.queuedAt() != null && alert.queuedAt().isBefore(cutoff)) {
                expired.add(alert);
            } else {
                included.add(alert);
            }
        }
        int includedCount = included.size() + snapshot.unreadableCount();
        if (includedCount == 0) {
            context.queue().removeFirst(snapshot.rawCount());
            context.metrics().discarded(DiscardReason.RETENTION_EXPIRED, expired.size());
            correlate("EXPIRED_ONLY", summaryTraceId, now, now, included, expired);
            refreshDepth();
            return;
        }
        LocalDateTime start =
                included.stream()
                        .map(QueuedAlert::queuedAt)
                        .filter(time -> time != null)
                        .min(Comparator.naturalOrder())
                        .orElse(now);
        String text =
                context.formatter()
                        .formatSummary(
                                start,
                                now,
                                includedCount,
                                included,
                                context.policy().summaryListLimit());
        Attempt attempt = sendLoop.send(text, Channel.SUMMARY);
        switch (attempt.result()) {
            case SENT -> {
                context.queue().removeFirst(snapshot.rawCount());
                context.metrics().discarded(DiscardReason.RETENTION_EXPIRED, expired.size());
                context.notificationLog()
                        .summary(EventType.SUMMARY_SENT, summaryTraceId, text, attempt.messageId());
                correlate("SENT", summaryTraceId, start, now, included, expired);
            }
            case PERMANENT -> {
                context.queue().removeFirst(snapshot.rawCount());
                context.metrics().discarded(DiscardReason.RETENTION_EXPIRED, expired.size());
                context.metrics().discarded(DiscardReason.SUMMARY_PERMANENT_FAILURE, includedCount);
                context.notificationLog()
                        .summary(EventType.SEND_FAILED, summaryTraceId, text, null);
                correlate("DISCARDED", summaryTraceId, start, now, included, expired);
            }
            default -> correlate("RETRY_LATER", summaryTraceId, start, now, included, expired);
        }
        refreshDepth();
    }

    /** 요약 ↔ 원본 알림 연결 로그 (plan.md §D.12 ③) — REPORT-001이 VictoriaLogs에서 이 줄로 잇는다. */
    private static void correlate(
            String result,
            String summaryTraceId,
            LocalDateTime start,
            LocalDateTime end,
            List<QueuedAlert> included,
            List<QueuedAlert> expired) {
        log.info(
                "[telegram-summary] result={} summary_trace_id={} period={}~{} included_count={}"
                        + " included_trace_ids={} expired_trace_ids={}",
                result,
                summaryTraceId,
                start,
                end,
                included.size(),
                included.stream().map(QueuedAlert::traceId).toList(),
                expired.stream().map(QueuedAlert::traceId).toList());
    }

    private void refreshDepth() {
        context.metrics().queueDepth(context.queue().size());
    }
}
