package com.aaa.notifier.telegram;

import com.aaa.notifier.filter.AlertDecision;
import com.aaa.notifier.filter.MarketSession;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code notification_log} 발송 사건 INSERT (REQ-021·023·024·033, [D-5] INSERT-ONLY 이벤트 소싱).
 *
 * <p>개별 알림 행은 결정의 {@code trace_id}로, 요약 행은 요약 시도마다 새로 발급한 {@code trace_id}로 쓴다(plan.md §D.12). 새
 * 조회는 하지 않는다 — 필요한 값은 전부 {@link AlertDecision}에 있다.
 *
 * <p><b>한 번만 시도하고 실패하면 삼킨다</b>(fail-open) — 이 INSERT는 디스패처 스레드에서 도므로 재시도는 그대로 다음 알림 지연이 되고, 발송은 이미
 * 끝났으므로 기록 실패가 발송을 취소하거나 되풀이하게 해서는 안 된다(REQ-002).
 */
@Slf4j
@RequiredArgsConstructor
public class JdbcNotificationLog implements NotificationLog {

    static final String ALERT_SQL =
            "INSERT INTO notification_log (event_type, notification_type, stock_id, horizon, tier,"
                    + " signal_class, score, confidence, trigger_price, prev_close, message_text,"
                    + " telegram_message_id, trace_id, trade_date, created_at)"
                    + " VALUES (?, 'TIMING', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    static final String SUMMARY_SQL =
            "INSERT INTO notification_log (event_type, notification_type, message_text,"
                    + " telegram_message_id, trace_id, created_at)"
                    + " VALUES (?, 'QUEUE_SUMMARY', ?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;
    private final TelegramMetrics metrics;
    private final Clock clock;

    @Override
    public void alert(
            EventType type, AlertDecision decision, String messageText, Long telegramMessageId) {
        try {
            jdbcTemplate.update(
                    ALERT_SQL,
                    type.name(),
                    decision.stockId(),
                    decision.horizon(),
                    decision.tier(),
                    decision.toGrade().name(),
                    decision.score(),
                    decision.confidence(),
                    decision.triggerPrice(),
                    decision.prevClose(),
                    messageText,
                    telegramMessageId,
                    decision.traceId(),
                    decision.tradeDate(),
                    now());
        } catch (DataAccessException e) {
            failed(type, decision.traceId(), e);
        }
    }

    @Override
    public void summary(
            EventType type, String traceId, String messageText, Long telegramMessageId) {
        try {
            jdbcTemplate.update(
                    SUMMARY_SQL, type.name(), messageText, telegramMessageId, traceId, now());
        } catch (DataAccessException e) {
            failed(type, traceId, e);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(MarketSession.KST));
    }

    private void failed(EventType type, String traceId, DataAccessException e) {
        metrics.recordFailure();
        log.warn(
                "[telegram-log] notification_log INSERT 실패 — 발송 결과는 유지, 재발송 없음 event_type={} trace_id={} error={}",
                type,
                traceId,
                e.getMessage());
    }
}
