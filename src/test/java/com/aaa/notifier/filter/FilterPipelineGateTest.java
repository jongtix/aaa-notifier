package com.aaa.notifier.filter;

import static com.aaa.notifier.filter.FilterTestFixtures.domesticReference;
import static com.aaa.notifier.filter.FilterTestFixtures.domesticTick;
import static com.aaa.notifier.filter.FilterTestFixtures.holderOf;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("FilterPipeline — 가드·확증·쿨다운 게이트와 결정 방출 (REQ-021~035, AC-7/12/13/14)")
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
    private void tick(FilterPipeline pipeline, String price) {
        accumulated += 1_000L;
        pipeline.onTradeTick(domesticTick(price, TEN, accumulated));
    }

    @Nested
    @DisplayName("확증 카운터 (REQ-033)")
    class Confirmation {

        @Test
        @DisplayName("HOLD→BUY(HOLD 진입, 확증 5회)는 5번째 연속 감지에서만 발송 후보가 된다")
        void holdEntry_requiresFiveDetections() {
            // Arrange
            FilterPipeline pipeline =
                    pipeline(Map.of("D20", FilterTestFixtures.uniformBands(Grade.BUY)));
            store.setGrade("005930", "D20", Grade.HOLD, Duration.ofHours(1));

            // Act
            for (int i = 0; i < 4; i++) {
                tick(pipeline, "30000");
            }
            List<AlertDecision> afterFour = List.copyOf(decisions);
            tick(pipeline, "30000");

            // Assert — 확증 대기 결정은 첫 대기 시점에 1회만 남는다
            assertThat(afterFour)
                    .singleElement()
                    .extracting(AlertDecision::suppressionReason)
                    .isEqualTo(SuppressionReason.CONFIRM_PENDING);
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
        @DisplayName("AC-12 — 4회 누적 후 확증 완료 전 유효 등급이 바뀌면 카운터가 리셋된다")
        void gradeChangeBeforeConfirmation_resetsCounter() {
            // Arrange
            FilterPipeline pipeline =
                    pipeline(Map.of("D20", FilterTestFixtures.buyStrongBuyBands()));
            store.setGrade("005930", "D20", Grade.HOLD, Duration.ofHours(1));
            for (int i = 0; i < 4; i++) {
                tick(pipeline, "29000"); // 양쪽 BUY — HOLD→BUY 후보 4회
            }
            assertThat(store.pending("005930", "D20").orElseThrow().count()).isEqualTo(4);

            // Act — 확증 전에 STRONG_BUY로 전환(29,000 → 30,600은 당일 변동폭 1,600 > ATR×2.5라 ATR 가드에 걸린다)
            tick(pipeline, "30600");

            // Assert — 이전 후보(HOLD→BUY, 4회)는 폐기되고 새 후보(BUY→STRONG_BUY)가 카운터 0에서 시작한다
            assertThat(store.gradeOf("005930", "D20")).isEqualTo(Grade.STRONG_BUY);
            PendingTransition pending = store.pending("005930", "D20").orElseThrow();
            assertThat(pending.from()).isEqualTo(Grade.BUY);
            assertThat(pending.to()).isEqualTo(Grade.STRONG_BUY);
            assertThat(pending.count()).isZero();
        }

        @Test
        @DisplayName("대기 중 데드존 체결은 카운터를 올리지도 리셋하지도 않는다")
        void deadZone_neitherIncrementsNorResets() {
            // Arrange — HOLD 유효 상태에서 HOLD→BUY 후보 2회 누적
            FilterPipeline pipeline =
                    pipeline(Map.of("D20", FilterTestFixtures.buyStrongBuyBands()));
            store.setGrade("005930", "D20", Grade.HOLD, Duration.ofHours(1));
            tick(pipeline, "29000");
            tick(pipeline, "29000");

            // Act
            tick(pipeline, "30200");

            // Assert
            assertThat(store.pending("005930", "D20").orElseThrow().count()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("쿨다운 (REQ-034)")
    class Cooldown {

        @Test
        @DisplayName("AC-13 — 같은 종목·horizon·Tier 쿨다운 중 재전환은 확증을 통과해도 억제된다")
        void activeCooldown_suppressesSameTier() {
            // Arrange — HOLD→SELL 발송(Tier 2 HOLD 진입, 쿨다운 30분) 후 SELL→HOLD→SELL 재진입
            FilterPipeline pipeline =
                    pipeline(Map.of("D20", FilterTestFixtures.uniformBands(Grade.SELL)));
            store.setGrade("005930", "D20", Grade.HOLD, Duration.ofHours(1));
            for (int i = 0; i < 5; i++) {
                tick(pipeline, "30000");
            }
            assertThat(decisions.getLast().isCandidate()).isTrue();
            store.setGrade("005930", "D20", Grade.HOLD, Duration.ofHours(1));

            // Act
            for (int i = 0; i < 5; i++) {
                tick(pipeline, "30000");
            }

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
            for (int i = 0; i < 5; i++) {
                tick(pipeline, "30000");
            }
            store.setGrade("005930", "D20", Grade.HOLD, Duration.ofHours(1));

            // Act — 개장 리셋(장전 cron이 호출하는 경로) 후 재전환
            store.clearCooldowns("005930", "D20");
            for (int i = 0; i < 5; i++) {
                tick(pipeline, "30000");
            }

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

            // Act — BUY→SELL 직접 전환(확증 2회)
            tick(pipeline, "30000");
            tick(pipeline, "30000");

            // Assert
            assertThat(decisions.getLast().isCandidate()).isTrue();
            assertThat(store.cooldowns).isEmpty();
        }
    }

    @Test
    @DisplayName("AC-14 — BUY→STRONG_BUY는 1회 감지만으로 확증 없이 Tier 1 발송 후보가 된다")
    void pureStrengthening_isImmediateTierOne() {
        // Arrange
        FilterPipeline pipeline =
                pipeline(Map.of("D20", FilterTestFixtures.uniformBands(Grade.STRONG_BUY)));
        store.setGrade("005930", "D20", Grade.BUY, Duration.ofHours(1));

        // Act
        tick(pipeline, "30000");

        // Assert
        assertThat(decisions)
                .singleElement()
                .satisfies(
                        decision -> {
                            assertThat(decision.isCandidate()).isTrue();
                            assertThat(decision.tier()).isEqualTo(1);
                            assertThat(decision.transitionType()).isNull();
                        });
    }

    @Nested
    @DisplayName("가드 연동 (REQ-021/024)")
    class Guards {

        @Test
        @DisplayName("AC-7 — 거래량 미달이면 확증 카운터로 진행하지 않고 대기하며 억제 결정은 1회만 남긴다")
        void volumeGuard_holdsCandidateWithoutCounting() {
            // Arrange
            accumulated = 0L;
            FilterPipeline pipeline =
                    pipeline(Map.of("D20", FilterTestFixtures.uniformBands(Grade.STRONG_BUY)));
            store.setGrade("005930", "D20", Grade.BUY, Duration.ofHours(1));

            // Act — 거래량 미달 체결 3회
            for (int i = 0; i < 3; i++) {
                tick(pipeline, "30000");
            }

            // Assert
            assertThat(decisions)
                    .singleElement()
                    .extracting(AlertDecision::suppressionReason)
                    .isEqualTo(SuppressionReason.GUARD_VOLUME);
            assertThat(store.pending("005930", "D20").orElseThrow().count()).isZero();
        }

        @Test
        @DisplayName("가드가 풀리면 대기 중이던 후보가 이어서 진행된다")
        void guardRelease_resumesCandidate() {
            // Arrange
            accumulated = 0L;
            FilterPipeline pipeline =
                    pipeline(Map.of("D20", FilterTestFixtures.uniformBands(Grade.STRONG_BUY)));
            store.setGrade("005930", "D20", Grade.BUY, Duration.ofHours(1));
            tick(pipeline, "30000");

            // Act — 거래량 충족
            accumulated = ENOUGH_VOLUME;
            tick(pipeline, "30000");

            // Assert
            assertThat(decisions.getLast().isCandidate()).isTrue();
        }
    }

    @Test
    @DisplayName("재전달된 체결(누적 거래량 비증가)은 확증 카운터를 두 번 올리지 않는다 (E2)")
    void redeliveredTick_doesNotDoubleCount() {
        // Arrange
        FilterPipeline pipeline =
                pipeline(Map.of("D20", FilterTestFixtures.uniformBands(Grade.BUY)));
        store.setGrade("005930", "D20", Grade.HOLD, Duration.ofHours(1));
        pipeline.onTradeTick(domesticTick("30000", TEN, 200_000L));

        // Act
        pipeline.onTradeTick(domesticTick("30000", TEN, 200_000L));

        // Assert
        assertThat(store.pending("005930", "D20").orElseThrow().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("결정 객체는 trigger_price·prev_close·trade_date·stock_id를 싣는다 (REQ-061)")
    void decision_carriesPersistenceFields() {
        // Arrange
        FilterPipeline pipeline =
                pipeline(Map.of("D20", FilterTestFixtures.uniformBands(Grade.STRONG_BUY)));
        store.setGrade("005930", "D20", Grade.BUY, Duration.ofHours(1));

        // Act
        tick(pipeline, "30100");

        // Assert
        AlertDecision decision = decisions.getLast();
        assertThat(decision.stockId()).isEqualTo(1L);
        assertThat(decision.triggerPrice()).isEqualByComparingTo("30100");
        assertThat(decision.prevClose()).isEqualByComparingTo("30000");
        assertThat(decision.tradeDate()).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(decision.traceId()).isNotBlank();
    }
}
