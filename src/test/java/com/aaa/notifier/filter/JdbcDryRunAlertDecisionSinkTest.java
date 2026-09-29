package com.aaa.notifier.filter;

import static com.aaa.notifier.filter.FilterTestFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.aaa.notifier.stream.Market;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName(
        "JdbcDryRunAlertDecisionSink — DRYRUN INSERT·3회 시도·fail-open (REQ-062, plan.md §C.3, E6)")
class JdbcDryRunAlertDecisionSinkTest {

    private FailingJdbcTemplate jdbcTemplate;
    private SimpleMeterRegistry registry;
    private JdbcDryRunAlertDecisionSink sink;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new FailingJdbcTemplate();
        registry = new SimpleMeterRegistry();
        sink =
                new JdbcDryRunAlertDecisionSink(
                        jdbcTemplate,
                        FilterMetrics.create(registry),
                        FilterTestFixtures.DOMESTIC_SESSION_CLOCK);
    }

    static AlertDecision candidate() {
        return new AlertDecision(
                1L,
                "005930",
                Market.DOMESTIC,
                "D20",
                Grade.HOLD,
                Grade.BUY,
                2,
                TransitionType.HOLD_ENTRY,
                bd("0.043000"),
                bd("0.712"),
                false,
                bd("30600"),
                bd("30000"),
                LocalDate.of(2026, 9, 29),
                null,
                "trace-candidate");
    }

    static AlertDecision suppressed() {
        return new AlertDecision(
                1L,
                "005930",
                Market.DOMESTIC,
                "D20",
                Grade.BUY,
                Grade.SELL,
                2,
                TransitionType.DIRECT,
                bd("-0.021000"),
                bd("0.604"),
                true,
                bd("29000"),
                bd("30000"),
                LocalDate.of(2026, 9, 29),
                SuppressionReason.GUARD_VOLUME,
                "trace-suppressed");
    }

    private double counter(String name, String... tags) {
        Counter found = registry.find(name).tags(tags).counter();
        return found == null ? 0.0 : found.count();
    }

    @Test
    @DisplayName(
            "발송 후보를 event_type=DRYRUN·notification_type=TIMING으로 INSERT한다 — message_text·telegram_message_id 생략")
    void candidate_isInsertedAsDryRun() {
        // Act
        sink.onDecision(candidate());

        // Assert
        assertThat(jdbcTemplate.attempts).isEqualTo(1);
        assertThat(jdbcTemplate.lastSql)
                .contains("'DRYRUN'")
                .contains("'TIMING'")
                .doesNotContain("message_text")
                .doesNotContain("telegram_message_id");
        assertThat(jdbcTemplate.lastArgs)
                .containsExactly(
                        1L,
                        "D20",
                        2,
                        "BUY",
                        bd("0.043000"),
                        bd("0.712"),
                        bd("30600"),
                        bd("30000"),
                        "trace-candidate",
                        LocalDate.of(2026, 9, 29),
                        LocalDateTime.of(2026, 9, 29, 10, 0));
        assertThat(counter("notifier.filter.decision", "outcome", "candidate")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("억제 후보도 DRYRUN 행을 남기고 억제 사유별 카운터를 올린다")
    void suppressed_isInsertedAndCounted() {
        // Act
        sink.onDecision(suppressed());

        // Assert
        assertThat(jdbcTemplate.attempts).isEqualTo(1);
        assertThat(
                        counter(
                                "notifier.filter.decision",
                                "outcome",
                                "suppressed",
                                "suppression_reason",
                                "guard_volume"))
                .isEqualTo(1.0);
    }

    @Nested
    @DisplayName("E6 — INSERT 실패 재시도 (최대 3회, 지연 없음)")
    class Retry {

        @Test
        @DisplayName("3회 모두 실패하면 예외를 전파하지 않고 실패 카운터를 1 올린다")
        void allAttemptsFail_failOpenWithCounter() {
            // Arrange
            jdbcTemplate.failuresBeforeSuccess = 3;

            // Act & Assert
            assertThatCode(() -> sink.onDecision(candidate())).doesNotThrowAnyException();
            assertThat(jdbcTemplate.attempts).isEqualTo(3);
            assertThat(counter("notifier.filter.decision.persist.failure")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("2회 실패 후 3회째 성공하면 폴백 카운터 없이 정상 처리된다")
        void thirdAttemptSucceeds_noFailureCounter() {
            // Arrange
            jdbcTemplate.failuresBeforeSuccess = 2;

            // Act
            sink.onDecision(candidate());

            // Assert
            assertThat(jdbcTemplate.attempts).isEqualTo(3);
            assertThat(counter("notifier.filter.decision.persist.failure")).isZero();
            assertThat(counter("notifier.filter.decision", "outcome", "candidate")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("첫 시도가 성공하면 재시도하지 않는다")
        void firstAttemptSucceeds_noRetry() {
            // Act
            sink.onDecision(candidate());

            // Assert
            assertThat(jdbcTemplate.attempts).isEqualTo(1);
        }
    }

    /**
     * 지정 횟수만큼 실패한 뒤 성공하는 가짜 템플릿 — SQL·인자를 기록한다.
     *
     * <p>Mockito 스텁 대신 서브클래스를 쓰는 이유는 {@code ReferenceDataLoaderTest}와 같다(SpotBugs SQL 인젝션 오탐 회피).
     */
    private static final class FailingJdbcTemplate extends JdbcTemplate {

        private int failuresBeforeSuccess;
        private int attempts;
        private String lastSql;
        private List<Object> lastArgs = List.of();

        @Override
        public int update(String sql, Object... args) {
            attempts++;
            lastSql = sql;
            lastArgs = new ArrayList<>(List.of(args));
            if (attempts <= failuresBeforeSuccess) {
                throw new DataAccessResourceFailureException("db down #" + attempts);
            }
            return 1;
        }
    }
}
