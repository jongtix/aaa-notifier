package com.aaa.notifier.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 결정 싱크 DRYRUN INSERT 통합 테스트 (REQ-062, AC-20) — V49와 같은 형태의 {@code notification_log}에 실제로 INSERT한다.
 *
 * <p>단위 테스트({@link JdbcDryRunAlertDecisionSinkTest})는 SQL 문자열과 인자만 본다. 컬럼 타입(DECIMAL 정밀도·DATETIME)과
 * NOT NULL 제약이 실제로 받아들여지는지는 실 MySQL로만 확인된다.
 */
@SpringBootTest(properties = "notifier.filter.enabled=true")
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
@Import(FixedClockConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("결정 싱크 DRYRUN INSERT 통합 테스트 (실 MySQL)")
class FilterDecisionSinkIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4").withInitScript("filter/schema.sql");

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AlertDecisionSink sink;

    private long stockId;

    @BeforeEach
    void setUp() {
        FilterDbFixtures.truncate(jdbcTemplate);
        stockId = FilterDbFixtures.insertStock(jdbcTemplate, "005930", "KOSPI");
    }

    private AlertDecision withStock(AlertDecision decision) {
        return new AlertDecision(
                stockId,
                decision.symbol(),
                decision.market(),
                decision.horizon(),
                decision.fromGrade(),
                decision.toGrade(),
                decision.tier(),
                decision.transitionType(),
                decision.score(),
                decision.confidence(),
                decision.lowConfidenceFlag(),
                decision.triggerPrice(),
                decision.prevClose(),
                decision.tradeDate(),
                decision.suppressionReason(),
                decision.traceId());
    }

    private Map<String, Object> rowOf(String traceId) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM notification_log WHERE trace_id = ?", traceId);
    }

    @Test
    @DisplayName("기본 싱크 빈은 DRYRUN 구현이다 (@ConditionalOnMissingBean 기본값)")
    void defaultSink_isDryRun() {
        assertThat(sink).isInstanceOf(JdbcDryRunAlertDecisionSink.class);
    }

    @Test
    @DisplayName("AC-20 ①② — 발송 후보가 DRYRUN 행 1건으로 남고 발송 컬럼은 NULL이다")
    void candidate_persistsSingleDryRunRow() {
        // Act
        sink.onDecision(withStock(JdbcDryRunAlertDecisionSinkTest.candidate()));

        // Assert
        Map<String, Object> row = rowOf("trace-candidate");
        assertThat(row)
                .containsEntry("event_type", "DRYRUN")
                .containsEntry("notification_type", "TIMING")
                .containsEntry("stock_id", stockId)
                .containsEntry("horizon", "D20")
                .containsEntry("signal_class", "BUY")
                .containsEntry("message_text", null)
                .containsEntry("telegram_message_id", null);
        assertThat(row.get("score").toString()).isEqualTo("0.043000");
        assertThat(row.get("confidence").toString()).isEqualTo("0.712");
        assertThat(row.get("trigger_price").toString()).isEqualTo("30600.0000");
        assertThat(row.get("prev_close").toString()).isEqualTo("30000.0000");
    }

    @Test
    @DisplayName("AC-20 ① — 억제 후보도 DRYRUN 행 1건을 남긴다 (tier·trade_date 포함)")
    void suppressed_persistsSingleDryRunRow() {
        // Act
        sink.onDecision(withStock(JdbcDryRunAlertDecisionSinkTest.suppressed()));

        // Assert
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM notification_log WHERE trace_id = ?",
                        Integer.class,
                        "trace-suppressed");
        assertThat(count).isEqualTo(1);
        Map<String, Object> row = rowOf("trace-suppressed");
        assertThat(row.get("tier").toString()).isEqualTo("2");
        assertThat(row.get("trade_date").toString()).isEqualTo("2026-09-29");
        assertThat(row.get("created_at")).isNotNull();
    }
}
