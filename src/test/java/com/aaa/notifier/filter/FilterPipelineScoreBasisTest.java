package com.aaa.notifier.filter;

import static com.aaa.notifier.filter.FilterTestFixtures.domesticReference;
import static com.aaa.notifier.filter.FilterTestFixtures.domesticTick;
import static com.aaa.notifier.filter.FilterTestFixtures.holderOf;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * displayScore/scoreBasis 분기 (SPEC-SIGNAL-PRICECURVE-001 REQ-008/010/011) — 장전 적재 커브 유무에 따라 {@link
 * TransitionGate#evaluate}가 만드는 {@link AlertDecision}이 어느 가격을 근거로 표시 score를 싣는지 검증한다.
 */
@DisplayName("TransitionGate — displayScore/scoreBasis 분기 (REQ-008/010/011)")
class FilterPipelineScoreBasisTest {

    private static final LocalTime TEN = LocalTime.of(10, 0);

    private InMemoryFilterStateStore store;
    private List<AlertDecision> decisions;

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryFilterStateStore();
        decisions = new ArrayList<>();
    }

    private FilterPipeline pipeline(Map<String, PriceScoreCurve> priceCurves) {
        StockReference reference =
                domesticReference(
                        Map.of("D20", FilterTestFixtures.uniformBands(Grade.STRONG_BUY)),
                        priceCurves);
        return FilterPipelines.create(
                holderOf(reference),
                store,
                decisions::add,
                new SimpleMeterRegistry(),
                FilterTestFixtures.DOMESTIC_SESSION_CLOCK);
    }

    private AlertDecision candidateDecision(Map<String, PriceScoreCurve> priceCurves) {
        FilterPipeline pipeline = pipeline(priceCurves);
        store.setGrade("005930", "D20", Grade.BUY, Duration.ofHours(1));

        pipeline.onTradeTick(domesticTick("30000", TEN, 100_000L));
        pipeline.onTradeTick(domesticTick("30000", TEN.plusMinutes(10), 200_000L));

        return decisions.stream().filter(AlertDecision::isCandidate).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("AC-008 — 장전 적재 커브가 있으면 발동가 보간값을 displayScore에 싣고 scoreBasis=TRIGGER_PRICE다")
    void curvePresent_interpolatesAtTriggerPriceWithTriggerPriceBasis() {
        // Arrange
        PriceScoreCurve curve =
                new PriceScoreCurve(
                        List.of(
                                new PricePoint(bd("29000"), bd("0.0200")),
                                new PricePoint(bd("30000"), bd("0.0400")),
                                new PricePoint(bd("31000"), bd("0.0100"))));

        // Act
        AlertDecision decision = candidateDecision(Map.of("D20", curve));

        // Assert — 발동가 30000은 그리드 포인트와 정확히 일치 → 그 포인트의 score 0.04
        assertThat(decision.displayScore()).isEqualByComparingTo("0.0400");
        assertThat(decision.scoreBasis()).isEqualTo(ScoreBasis.TRIGGER_PRICE);
    }

    @Test
    @DisplayName("AC-010 — 장전 적재 커브가 없으면 전일 종가 기준 score로 폴백하고 scoreBasis=PREV_CLOSE_FALLBACK다")
    void curveAbsent_fallsBackToSignalScoreWithPrevCloseBasis() {
        // Arrange
        store.saveSignal(
                "005930",
                "D20",
                new SignalSnapshot(
                        "STRONG_BUY", bd("0.0555"), bd("0.700"), LocalDate.of(2026, 9, 29), "sig"));

        // Act
        AlertDecision decision = candidateDecision(Map.of());

        // Assert
        assertThat(decision.displayScore()).isEqualByComparingTo("0.0555");
        assertThat(decision.scoreBasis()).isEqualTo(ScoreBasis.PREV_CLOSE_FALLBACK);
    }

    @Test
    @DisplayName("AC-011 — 커브 유무 두 경로 모두 displayScore가 null이 아니다(scoreBasis로만 분기)")
    void bothBranches_neverLeaveDisplayScoreNull() {
        store.saveSignal(
                "005930",
                "D20",
                new SignalSnapshot(
                        "STRONG_BUY", bd("0.0555"), bd("0.700"), LocalDate.of(2026, 9, 29), "sig"));
        PriceScoreCurve curve =
                new PriceScoreCurve(List.of(new PricePoint(bd("30000"), bd("0.0400"))));

        AlertDecision withCurve = candidateDecision(Map.of("D20", curve));
        store = new InMemoryFilterStateStore();
        decisions = new ArrayList<>();
        store.saveSignal(
                "005930",
                "D20",
                new SignalSnapshot(
                        "STRONG_BUY", bd("0.0555"), bd("0.700"), LocalDate.of(2026, 9, 29), "sig"));
        AlertDecision withoutCurve = candidateDecision(Map.of());

        assertThat(withCurve.displayScore()).isNotNull();
        assertThat(withoutCurve.displayScore()).isNotNull();
    }
}
