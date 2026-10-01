package com.aaa.notifier.filter;

import static com.aaa.notifier.filter.FilterTestFixtures.domesticReference;
import static com.aaa.notifier.filter.FilterTestFixtures.domesticTick;
import static com.aaa.notifier.filter.FilterTestFixtures.holderOf;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("FilterPipeline — 가드·유지 확증·쿨다운 게이트와 결정 방출 (REQ-021~035, FILTER-002, AC-7/12/13/14)")
class FilterPipelineGateTest {

    /** 개장 후 60분(가드 시간대 통과) — 기대 누적 거래량 = 1,000,000 × 60/390 × 0.3 ≈ 46,154. */
    private static final LocalTime TEN = LocalTime.of(10, 0);

    private static final long ENOUGH_VOLUME = 100_000L;

    private InMemoryFilterStateStore store;
    private List<AlertDecision> decisions;
    private SimpleMeterRegistry registry;
    private long accumulated;

    @BeforeEach
    void setUp() {
        store = new InMemoryFilterStateStore();
        decisions = new ArrayList<>();
        registry = new SimpleMeterRegistry();
        accumulated = ENOUGH_VOLUME;
    }

    private FilterPipeline pipeline(Map<String, HorizonBands> bands) {
        return FilterPipelines.create(
                holderOf(domesticReference(bands)),
                store,
                decisions::add,
                registry,
                FilterTestFixtures.DOMESTIC_SESSION_CLOCK);
    }

    /** 누적 거래량을 매번 늘려 재전달 멱등 판정에 걸리지 않는 체결. */
    private void tick(FilterPipeline pipeline, String price, LocalTime time) {
        accumulated += 1_000L;
        pipeline.onTradeTick(domesticTick(price, time, accumulated));
    }

    private static Instant kst(LocalTime time) {
        return ZonedDateTime.of(LocalDate.of(2026, 9, 29), time, MarketSession.KST).toInstant();
    }

    private double dwellStage(String outcome) {
        Counter counter =
                registry.find(FilterMetrics.STAGE_COUNTER)
                        .tag("stage", "dwell")
                        .tag("outcome", outcome)
                        .counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Nested
    @DisplayName("유지 확증 (FILTER-002 REQ-001/005/006)")
    class Dwell {

        @Test
        @DisplayName("HOLD→BUY(HOLD 진입, 유지 10분)는 전환 체결 10분 뒤 체결에서만 발송 후보가 된다")
        void holdEntry_requiresTenMinuteDwell() {
            // Arrange
            FilterPipeline pipeline =
                    pipeline(Map.of("D20", FilterTestFixtures.uniformBands(Grade.BUY)));
            store.setGrade("005930", "D20", Grade.HOLD, Duration.ofHours(1));

            // Act
            tick(pipeline, "30000", TEN);
            tick(pipeline, "30000", TEN.plusMinutes(9).plusSeconds(50));
            List<AlertDecision> beforeDwell = List.copyOf(decisions);
            tick(pipeline, "30000", TEN.plusMinutes(10));

            // Assert — 유지 대기 결정은 첫 대기 시점에 1회만 남는다
            assertThat(beforeDwell)
                    .singleElement()
                    .extracting(AlertDecision::suppressionReason)
                    .isEqualTo(SuppressionReason.DWELL_PENDING);
            assertThat(decisions).hasSize(2);
            assertThat(decisions.getLast())
                    .extracting(
                            AlertDecision::isCandidate,
                            AlertDecision::fromGrade,
                            AlertDecision::toGrade,
                            AlertDecision::transitionType)
                    .containsExactly(true, Grade.HOLD, Grade.BUY, TransitionType.HOLD_ENTRY);
        }

        @Test
        @DisplayName("AC-12(FILTER-001) — 유지 확증 전 유효 등급이 바뀌면 새 후보의 측정이 그 교차 체결부터 다시 시작된다")
        void gradeChangeBeforeDwell_restartsMeasurement() {
            // Arrange
            FilterPipeline pipeline =
                    pipeline(Map.of("D20", FilterTestFixtures.buyStrongBuyBands()));
            store.setGrade("005930", "D20", Grade.HOLD, Duration.ofHours(1));
            tick(pipeline, "29000", TEN); // 양쪽 BUY — HOLD→BUY 후보, 전환 체결 10:00:00

            // Act — 유지 확증 전에 STRONG_BUY로 전환(29,000 → 30,600은 당일 변동폭 1,600 > ATR×2.5라 ATR 가드에 걸린다)
            tick(pipeline, "30600", TEN.plusMinutes(1));

            // Assert — 이전 후보(HOLD→BUY)는 버려지고 새 후보(BUY→STRONG_BUY)가 10:01:00부터 잰다
            assertThat(store.gradeOf("005930", "D20")).isEqualTo(Grade.STRONG_BUY);
            PendingTransition pending = store.pending("005930", "D20").orElseThrow();
            assertThat(pending.from()).isEqualTo(Grade.BUY);
            assertThat(pending.to()).isEqualTo(Grade.STRONG_BUY);
            assertThat(pending.since()).isEqualTo(kst(TEN.plusMinutes(1)));
        }

        @Test
        @DisplayName("대기 중 데드존 체결은 전환 체결 시각을 바꾸지도 유지 단계를 평가하지도 않는다")
        void deadZone_neitherResetsNorEvaluates() {
            // Arrange — HOLD 유효 상태에서 HOLD→BUY 후보(전환 체결 10:00:00)
            FilterPipeline pipeline =
                    pipeline(Map.of("D20", FilterTestFixtures.buyStrongBuyBands()));
            store.setGrade("005930", "D20", Grade.HOLD, Duration.ofHours(1));
            tick(pipeline, "29000", TEN);
            double blocksBefore = dwellStage("block");

            // Act
            tick(pipeline, "30200", TEN.plusMinutes(1));

            // Assert
            assertThat(store.pending("005930", "D20").orElseThrow().since()).isEqualTo(kst(TEN));
            assertThat(dwellStage("block")).isEqualTo(blocksBefore);
        }
    }

    @Nested
    @DisplayName("쿨다운 (REQ-034)")
    class Cooldown {

        @Test
        @DisplayName("AC-13 — 같은 종목·horizon·Tier 쿨다운 중 재전환은 유지 확증을 통과해도 억제된다")
        void activeCooldown_suppressesSameTier() {
            // Arrange — HOLD→SELL 발송(Tier 2 HOLD 진입, 유지 10분, 쿨다운 30분) 후 SELL→HOLD→SELL 재진입
            FilterPipeline pipeline =
                    pipeline(Map.of("D20", FilterTestFixtures.uniformBands(Grade.SELL)));
            store.setGrade("005930", "D20", Grade.HOLD, Duration.ofHours(1));
            tick(pipeline, "30000", TEN);
            tick(pipeline, "30000", TEN.plusMinutes(10));
            assertThat(decisions.getLast().isCandidate()).isTrue();
            store.setGrade("005930", "D20", Grade.HOLD, Duration.ofHours(1));

            // Act
            tick(pipeline, "30000", TEN.plusMinutes(11));
            tick(pipeline, "30000", TEN.plusMinutes(21));

            // Assert
            assertThat(decisions.getLast().suppressionReason())
                    .isEqualTo(SuppressionReason.COOLDOWN_ACTIVE);
            assertThat(store.pending("005930", "D20")).isEmpty();
        }

        @Test
        @DisplayName("AC-13 후단 — 장 시작 리셋 후에는 같은 재전환이 발송 후보가 된다")
        void marketOpenReset_clearsCooldown() {
            // Arrange
            FilterPipeline pipeline =
                    pipeline(Map.of("D20", FilterTestFixtures.uniformBands(Grade.SELL)));
            store.setGrade("005930", "D20", Grade.HOLD, Duration.ofHours(1));
            tick(pipeline, "30000", TEN);
            tick(pipeline, "30000", TEN.plusMinutes(10));
            store.setGrade("005930", "D20", Grade.HOLD, Duration.ofHours(1));

            // Act — 개장 리셋(장전 cron이 호출하는 경로) 후 재전환
            store.clearCooldowns("005930", "D20");
            tick(pipeline, "30000", TEN.plusMinutes(11));
            tick(pipeline, "30000", TEN.plusMinutes(21));

            // Assert
            assertThat(decisions.getLast().isCandidate()).isTrue();
        }

        @Test
        @DisplayName("직접 전환은 쿨다운이 없다(0분) — 키를 남기지 않는다")
        void directTransition_hasNoCooldown() {
            // Arrange
            FilterPipeline pipeline =
                    pipeline(Map.of("D20", FilterTestFixtures.uniformBands(Grade.SELL)));
            store.setGrade("005930", "D20", Grade.BUY, Duration.ofHours(1));

            // Act — BUY→SELL 직접 전환(유지 5분)
            tick(pipeline, "30000", TEN);
            tick(pipeline, "30000", TEN.plusMinutes(5));

            // Assert
            assertThat(decisions.getLast().isCandidate()).isTrue();
            assertThat(store.cooldowns).isEmpty();
        }
    }

    @Test
    @DisplayName(
            "AC-14(FILTER-001) — BUY→STRONG_BUY는 순수 강도 강화 유지 시간(10분)을 채운 뒤 Tier 1 발송 후보 1건으로 종결된다")
    void pureStrengthening_isTierOneAfterDwell() {
        // Arrange
        FilterPipeline pipeline =
                pipeline(Map.of("D20", FilterTestFixtures.uniformBands(Grade.STRONG_BUY)));
        store.setGrade("005930", "D20", Grade.BUY, Duration.ofHours(1));

        // Act
        tick(pipeline, "30000", TEN);
        tick(pipeline, "30000", TEN.plusMinutes(10));

        // Assert — 교차 직후는 유지 대기, 종결 결정은 발송 후보 1건
        assertThat(decisions.getFirst().suppressionReason())
                .isEqualTo(SuppressionReason.DWELL_PENDING);
        assertThat(decisions.stream().filter(AlertDecision::isCandidate))
                .singleElement()
                .satisfies(
                        decision -> {
                            assertThat(decision.tier()).isEqualTo(1);
                            assertThat(decision.transitionType()).isNull();
                        });
    }

    @Nested
    @DisplayName("가드 연동 (REQ-021/024, FILTER-002 REQ-009)")
    class Guards {

        @Test
        @DisplayName("AC-7 — 거래량 미달이면 유지 단계로 진행하지 않고 대기하며 억제 결정은 1회만 남긴다")
        void volumeGuard_holdsCandidateWithoutDwellEvaluation() {
            // Arrange
            accumulated = 0L;
            FilterPipeline pipeline =
                    pipeline(Map.of("D20", FilterTestFixtures.uniformBands(Grade.STRONG_BUY)));
            store.setGrade("005930", "D20", Grade.BUY, Duration.ofHours(1));

            // Act — 거래량 미달 체결 3회
            for (int i = 0; i < 3; i++) {
                tick(pipeline, "30000", TEN.plusSeconds(10L * i));
            }

            // Assert
            assertThat(decisions)
                    .singleElement()
                    .extracting(AlertDecision::suppressionReason)
                    .isEqualTo(SuppressionReason.GUARD_VOLUME);
            assertThat(dwellStage("block") + dwellStage("pass")).isZero();
        }

        @Test
        @DisplayName("가드가 풀리면 대기 중이던 후보가 전환 체결 이후 누적된 유지 시간으로 판정된다")
        void guardRelease_resumesCandidateWithAccumulatedDwell() {
            // Arrange
            accumulated = 0L;
            FilterPipeline pipeline =
                    pipeline(Map.of("D20", FilterTestFixtures.uniformBands(Grade.STRONG_BUY)));
            store.setGrade("005930", "D20", Grade.BUY, Duration.ofHours(1));
            tick(pipeline, "30000", TEN);

            // Act — 거래량 충족, 가드 대기 동안에도 유지 시간이 흘렀다(10:00 → 10:10)
            accumulated = ENOUGH_VOLUME;
            tick(pipeline, "30000", TEN.plusMinutes(10));

            // Assert
            assertThat(decisions.getLast().isCandidate()).isTrue();
        }
    }

    @Test
    @DisplayName("재전달된 체결(누적 거래량 비증가)은 게이트를 다시 평가하지 않는다 (E2)")
    void redeliveredTick_isNotEvaluatedTwice() {
        // Arrange
        FilterPipeline pipeline =
                pipeline(Map.of("D20", FilterTestFixtures.uniformBands(Grade.BUY)));
        store.setGrade("005930", "D20", Grade.HOLD, Duration.ofHours(1));
        pipeline.onTradeTick(domesticTick("30000", TEN, 200_000L));

        // Act
        pipeline.onTradeTick(domesticTick("30000", TEN, 200_000L));

        // Assert
        assertThat(dwellStage("block")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("결정 객체는 trigger_price·prev_close·trade_date·stock_id를 싣는다 (REQ-061)")
    void decision_carriesPersistenceFields() {
        // Arrange
        FilterPipeline pipeline =
                pipeline(Map.of("D20", FilterTestFixtures.uniformBands(Grade.STRONG_BUY)));
        store.setGrade("005930", "D20", Grade.BUY, Duration.ofHours(1));
        tick(pipeline, "30000", TEN);

        // Act
        tick(pipeline, "30100", TEN.plusMinutes(10));

        // Assert — 발송 후보임을 먼저 확인한다(대기 억제 결정을 검사하며 조용히 통과하지 않도록, N6)
        AlertDecision decision = decisions.getLast();
        assertThat(decision)
                .extracting(
                        AlertDecision::isCandidate,
                        AlertDecision::stockId,
                        AlertDecision::tradeDate)
                .containsExactly(true, 1L, LocalDate.of(2026, 9, 29));
        assertThat(decision.triggerPrice()).isEqualByComparingTo("30100");
        assertThat(decision.prevClose()).isEqualByComparingTo("30000");
        assertThat(decision.traceId()).isNotBlank();
    }
}
