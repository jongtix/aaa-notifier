package com.aaa.notifier.filter;

import static com.aaa.notifier.filter.FilterTestFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.notifier.stream.Market;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("GuardEvaluator — 거래량·시간대·ATR 가드와 단락 평가 (REQ-021/022/024, AC-7~AC-9)")
class GuardEvaluatorTest {

    /** 거래량 30%·개장 15분·마감 10분·ATR 2.5배 (plan.md §F 잠정값). */
    private static final FilterProperties.Guard GUARD =
            new FilterProperties.Guard(
                    new BigDecimal("0.30"),
                    Duration.ofMinutes(15),
                    Duration.ofMinutes(10),
                    new BigDecimal("2.5"));

    /** 평균 일 거래량 1,000,000 · ATR 300 · 국내. */
    private static final StockReference REFERENCE =
            new StockReference(
                    1L,
                    "005930",
                    Market.DOMESTIC,
                    LocalDate.of(2026, 9, 28),
                    bd("30000"),
                    bd("1000000"),
                    bd("300"),
                    Map.of());

    /** 12:15 = 개장 후 195분 = 세션의 50% → 기대 누적 500,000, 30% 임계 = 150,000. */
    private static final LocalTime MIDDAY = LocalTime.of(12, 15);

    private SimpleMeterRegistry registry;
    private GuardEvaluator evaluator;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        evaluator = new GuardEvaluator(GUARD, FilterMetrics.create(registry));
    }

    /** 국내 정규장 390분 중 KST {@code time} 시점. */
    private static ZonedDateTime at(LocalTime time) {
        return ZonedDateTime.of(LocalDate.of(2026, 9, 29), time, MarketSession.KST);
    }

    private double stage(String stage, String outcome) {
        Counter counter =
                registry.find(FilterMetrics.STAGE_COUNTER)
                        .tag("stage", stage)
                        .tag("outcome", outcome)
                        .counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Nested
    @DisplayName("AC-7 — 거래량 가드")
    class VolumeGuard {

        @Test
        @DisplayName("같은 시각 기준 평균 대비 누적 거래량이 임계 미만이면 억제한다")
        void thinVolume_isSuppressed() {
            Optional<SuppressionReason> result =
                    evaluator.evaluate(REFERENCE, at(MIDDAY), 149_999L, bd("100"));

            assertThat(result).contains(SuppressionReason.GUARD_VOLUME);
            assertThat(stage("guard_volume", "block")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("임계 이상이면 통과한다")
        void enoughVolume_passes() {
            Optional<SuppressionReason> result =
                    evaluator.evaluate(REFERENCE, at(MIDDAY), 150_000L, bd("100"));

            assertThat(result).isEmpty();
            assertThat(stage("guard_volume", "pass")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("평균 거래량을 산출할 수 없으면 억제한다(과소 알림 방향)")
        void unknownAverage_isSuppressed() {
            StockReference noAverage =
                    new StockReference(
                            1L,
                            "005930",
                            Market.DOMESTIC,
                            LocalDate.of(2026, 9, 28),
                            bd("30000"),
                            null,
                            bd("300"),
                            Map.of());

            assertThat(evaluator.evaluate(noAverage, at(MIDDAY), 999_999L, bd("100")))
                    .contains(SuppressionReason.GUARD_VOLUME);
        }
    }

    @Nested
    @DisplayName("AC-8 — 시간대 가드")
    class TimeOfDayGuard {

        @Test
        @DisplayName("개장 후 15분 이내면 억제한다")
        void withinOpenExclusion_isSuppressed() {
            assertThat(evaluator.evaluate(REFERENCE, at(LocalTime.of(9, 14)), 5_000_000L, bd("0")))
                    .contains(SuppressionReason.GUARD_TIME_OF_DAY);
        }

        @Test
        @DisplayName("마감 전 10분 이내면 억제한다")
        void withinCloseExclusion_isSuppressed() {
            assertThat(evaluator.evaluate(REFERENCE, at(LocalTime.of(15, 21)), 5_000_000L, bd("0")))
                    .contains(SuppressionReason.GUARD_TIME_OF_DAY);
        }

        @Test
        @DisplayName("개장 전 체결은 억제한다")
        void beforeOpen_isSuppressed() {
            assertThat(evaluator.evaluate(REFERENCE, at(LocalTime.of(8, 50)), 5_000_000L, bd("0")))
                    .contains(SuppressionReason.GUARD_TIME_OF_DAY);
        }

        @Test
        @DisplayName("해외는 ET 기준 개장 시각(09:30)으로 판정한다 — 23:40 KST = 10:40 EDT는 통과")
        void overseasUsesEasternOpen() {
            StockReference aapl =
                    new StockReference(
                            9L,
                            "AAPL",
                            Market.OVERSEAS,
                            LocalDate.of(2026, 9, 28),
                            bd("100"),
                            bd("1000"),
                            bd("2"),
                            Map.of());
            ZonedDateTime tick =
                    ZonedDateTime.of(
                                    LocalDate.of(2026, 9, 29),
                                    LocalTime.of(23, 40),
                                    MarketSession.KST)
                            .withZoneSameInstant(MarketSession.of(Market.OVERSEAS).zone());

            assertThat(evaluator.evaluate(aapl, tick, 1_000L, bd("0"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("AC-9 — ATR 가드 + 단락 평가 순서")
    class AtrGuard {

        @Test
        @DisplayName("당일 변동폭이 ATR × 2.5(=750)를 넘으면 억제한다")
        void overheated_isSuppressed() {
            Optional<SuppressionReason> result =
                    evaluator.evaluate(REFERENCE, at(MIDDAY), 900_000L, bd("751"));

            assertThat(result).contains(SuppressionReason.GUARD_ATR);
            assertThat(stage("guard_volume", "pass")).isEqualTo(1.0);
            assertThat(stage("guard_time", "pass")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("ATR을 산출할 수 없으면(이력 부족) 억제한다")
        void unknownAtr_isSuppressed() {
            StockReference noAtr =
                    new StockReference(
                            1L,
                            "005930",
                            Market.DOMESTIC,
                            LocalDate.of(2026, 9, 28),
                            bd("30000"),
                            bd("1000000"),
                            null,
                            Map.of());

            assertThat(evaluator.evaluate(noAtr, at(MIDDAY), 900_000L, bd("10")))
                    .contains(SuppressionReason.GUARD_ATR);
        }

        @Test
        @DisplayName("거래량에서 억제되면 시간대·ATR 가드는 평가하지 않는다")
        void volumeBlock_shortCircuitsRemainingGuards() {
            // Act — 거래량 미달 + 시간대(개장 직후)·ATR 과열 조건도 동시에 성립
            Optional<SuppressionReason> result =
                    evaluator.evaluate(REFERENCE, at(LocalTime.of(9, 5)), 0L, bd("9999"));

            // Assert
            assertThat(result).contains(SuppressionReason.GUARD_VOLUME);
            assertThat(stage("guard_time", "pass") + stage("guard_time", "block")).isZero();
            assertThat(stage("guard_atr", "pass") + stage("guard_atr", "block")).isZero();
        }

        @Test
        @DisplayName("시간대에서 억제되면 ATR 가드는 평가하지 않는다")
        void timeBlock_shortCircuitsAtr() {
            Optional<SuppressionReason> result =
                    evaluator.evaluate(REFERENCE, at(LocalTime.of(15, 25)), 5_000_000L, bd("9999"));

            assertThat(result).contains(SuppressionReason.GUARD_TIME_OF_DAY);
            assertThat(stage("guard_atr", "pass") + stage("guard_atr", "block")).isZero();
        }
    }
}
