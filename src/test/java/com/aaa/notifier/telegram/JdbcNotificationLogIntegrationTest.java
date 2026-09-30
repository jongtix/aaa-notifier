package com.aaa.notifier.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.notifier.filter.AlertDecision;
import com.aaa.notifier.filter.MarketSession;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code notification_log} 발송 사건 INSERT를 V49와 같은 형태의 실 MySQL로 검증한다 (REQ-021·023·024·033,
 * AC-07·AC-13 ①).
 *
 * <p>컬럼 타입(DECIMAL 정밀도·TEXT·BIGINT)과 NULL 허용 여부는 실 MySQL로만 확인된다.
 */
@Testcontainers
@Tag("integration")
@DisplayName("JdbcNotificationLog — 발송 사건 INSERT (실 MySQL)")
class JdbcNotificationLogIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4").withInitScript("filter/schema.sql");

    private static final ZonedDateTime NOW =
            ZonedDateTime.of(2026, 9, 29, 10, 0, 0, 0, MarketSession.KST);

    private static JdbcTemplate jdbcTemplate;

    private JdbcNotificationLog notificationLog;
    private long stockId;

    @BeforeAll
    static void connect() {
        jdbcTemplate =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM notification_log");
        jdbcTemplate.update("DELETE FROM stocks");
        jdbcTemplate.update(
                "INSERT INTO stocks (symbol, market, asset_type, created_at, updated_at)"
                        + " VALUES ('005930', 'KOSPI', 'STOCK', NOW(), NOW())");
        stockId =
                Objects.requireNonNull(
                        jdbcTemplate.queryForObject("SELECT id FROM stocks", Long.class));
        notificationLog =
                new JdbcNotificationLog(
                        jdbcTemplate,
                        TelegramMetrics.create(new SimpleMeterRegistry()),
                        Clock.fixed(NOW.toInstant(), MarketSession.KST));
    }

    private AlertDecision decision() {
        AlertDecision base = TelegramAlerts.candidate("trace-sent");
        return new AlertDecision(
                stockId,
                base.symbol(),
                base.market(),
                base.horizon(),
                base.fromGrade(),
                base.toGrade(),
                base.tier(),
                base.transitionType(),
                base.score(),
                base.confidence(),
                base.lowConfidenceFlag(),
                base.triggerPrice(),
                base.prevClose(),
                base.tradeDate(),
                null,
                base.traceId());
    }

    private Map<String, Object> onlyRow() {
        return jdbcTemplate.queryForMap("SELECT * FROM notification_log");
    }

    @Test
    @DisplayName("AC-07 — SENT 행에 결정 필드·실제 보낸 본문·telegram_message_id·KST created_at이 담긴다")
    void sentRow() {
        notificationLog.alert(EventType.SENT, decision(), "<b>body</b>", 777L);

        Map<String, Object> row = onlyRow();
        assertThat(row)
                .containsEntry("event_type", "SENT")
                .containsEntry("notification_type", "TIMING")
                .containsEntry("stock_id", stockId)
                .containsEntry("horizon", "D20")
                .containsEntry("signal_class", "BUY")
                .containsEntry("message_text", "<b>body</b>")
                .containsEntry("telegram_message_id", 777L)
                .containsEntry("trace_id", "trace-sent");
        assertThat(row.get("score").toString()).isEqualTo("0.043000");
        assertThat(row.get("trigger_price").toString()).isEqualTo("30600.0000");
        assertThat(row.get("prev_close").toString()).isEqualTo("30000.0000");
        assertThat(row.get("created_at").toString()).startsWith("2026-09-29T10:00");
    }

    @Test
    @DisplayName("AC-10 — SEND_FAILED 행은 보내려던 본문을 담고 telegram_message_id는 NULL이다")
    void sendFailedRow() {
        notificationLog.alert(EventType.SEND_FAILED, decision(), "body", null);

        assertThat(onlyRow())
                .containsEntry("event_type", "SEND_FAILED")
                .containsEntry("message_text", "body")
                .containsEntry("telegram_message_id", null);
    }

    @Test
    @DisplayName("AC-13 ① — 요약 행은 QUEUE_SUMMARY이고 종목 무관 컬럼은 NULL, 자기 trace_id를 쓴다")
    void summaryRow() {
        notificationLog.summary(EventType.SUMMARY_SENT, "summary-trace", "장애 기간 …", 900L);

        assertThat(onlyRow())
                .containsEntry("event_type", "SUMMARY_SENT")
                .containsEntry("notification_type", "QUEUE_SUMMARY")
                .containsEntry("stock_id", null)
                .containsEntry("horizon", null)
                .containsEntry("tier", null)
                .containsEntry("trace_id", "summary-trace");
    }
}
