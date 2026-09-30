package com.aaa.notifier.telegram;

import com.aaa.notifier.filter.AlertDecision;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@code stream:alert} 발행 (REQ-NOTIFIER-TELEGRAM-041, TECHSPEC §5.1, 설계 초안 [G-5]) — trader(Phase
 * 4)가 소비하는 계약면이다.
 *
 * <p>필드 13개, 값은 전부 문자열, {@code null}은 빈 문자열. 길이는 근사 MAXLEN({@code ~ 500})으로 묶는다 — DLQ와 달리 보존 대상이
 * 아니라 최근 발송 이벤트 버퍼이므로 매크로 노드 단위 근사 트리밍으로 충분하다.
 *
 * <p>발행 실패는 이미 성립한 {@code SENT}를 되돌리거나 재발송하지 않는다 — WARN + 발행 실패 계측만 남긴다(fail-open).
 */
// @MX:ANCHOR: [AUTO] notifier ↔ trader(Phase 4) 계약면 — stream:alert 엔트리 필드 집합이 여기서 정해진다
// @MX:REASON: 필드 이름·문자열 표현·null=빈 문자열 규칙이 바뀌면 trader 소비자가 깨진다(REQ-041, AC-14 ①)
@Slf4j
@RequiredArgsConstructor
public class RedisAlertPublisher implements AlertPublisher {

    /** TECHSPEC §5.1 발송 완료 알림 스트림. */
    static final String KEY = "stream:alert";

    private static final String NOTIFICATION_TYPE = "TIMING";

    private final StringRedisTemplate redis;
    private final long maxLen;
    private final TelegramMetrics metrics;

    @Override
    public void publish(AlertDecision decision, Long telegramMessageId, OffsetDateTime sentAt) {
        Map<String, String> fields =
                Map.ofEntries(
                        Map.entry("tier", String.valueOf(decision.tier())),
                        Map.entry("stock_id", String.valueOf(decision.stockId())),
                        Map.entry("symbol", decision.symbol()),
                        Map.entry("market", decision.market().name()),
                        Map.entry("horizon", decision.horizon()),
                        Map.entry("signal", decision.toGrade().name()),
                        Map.entry("score", plain(decision.score())),
                        Map.entry("confidence", plain(decision.confidence())),
                        Map.entry("trade_date", decision.tradeDate().toString()),
                        Map.entry("notification_type", NOTIFICATION_TYPE),
                        Map.entry("trace_id", decision.traceId()),
                        Map.entry("sent_at", sentAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)),
                        Map.entry(
                                "telegram_message_id",
                                telegramMessageId == null
                                        ? ""
                                        : String.valueOf(telegramMessageId)));
        try {
            redis.opsForStream()
                    .add(
                            MapRecord.create(KEY, fields),
                            XAddOptions.maxlen(maxLen).approximateTrimming(true));
        } catch (DataAccessException e) {
            metrics.publishFailure();
            log.warn(
                    "[telegram-alert-stream] stream:alert 발행 실패 — SENT는 유지, 재발송 없음 trace_id={} error={}",
                    decision.traceId(),
                    e.getMessage());
        }
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }
}
