package com.aaa.notifier.filter;

import static com.aaa.notifier.filter.FilterTestFixtures.bd;
import static com.aaa.notifier.filter.FilterTestFixtures.domesticReference;
import static com.aaa.notifier.filter.FilterTestFixtures.domesticTick;
import static com.aaa.notifier.filter.FilterTestFixtures.holderOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.notifier.stream.SignalObservation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName(
        "FilterPipeline — ※셀 해소: Tier 1 + HOLD 진입·STRONG 유지 시간 (REQ-042, FILTER-002 REQ-008, AC-16)")
class FilterPipelineStarCellTest {

    private List<AlertDecision> decisions;
    private FilterPipeline pipeline;
    private long accumulated = 100_000L;

    @BeforeEach
    void setUp() {
        InMemoryFilterStateStore store = new InMemoryFilterStateStore();
        decisions = new ArrayList<>();
        pipeline =
                FilterPipelines.create(
                        holderOf(
                                domesticReference(
                                        Map.of(
                                                "D20",
                                                FilterTestFixtures.uniformBands(
                                                        Grade.STRONG_BUY)))),
                        store,
                        decisions::add,
                        new SimpleMeterRegistry(),
                        FilterTestFixtures.DOMESTIC_SESSION_CLOCK);
        store.setGrade("005930", "D20", Grade.HOLD, Duration.ofHours(1));
    }

    private void tick(LocalTime time) {
        accumulated += 1_000L;
        pipeline.onTradeTick(domesticTick("30000", time, accumulated));
    }

    @Test
    @DisplayName("AC-16 — HOLD→STRONG_BUY는 Tier 1로 분류되지만 HOLD 진입·STRONG 유지 시간(15분) 미충족이면 대기한다")
    void starCell_isTierOneButStillRequiresDwell() {
        // Act
        tick(LocalTime.of(10, 0));
        tick(LocalTime.of(10, 14, 50));

        // Assert
        assertThat(decisions)
                .singleElement()
                .extracting(
                        AlertDecision::tier,
                        AlertDecision::transitionType,
                        AlertDecision::suppressionReason)
                .containsExactly(1, TransitionType.HOLD_ENTRY, SuppressionReason.DWELL_PENDING);
    }

    @Test
    @DisplayName("AC-16 후단 — 유지 시간 충족 후에는 저확신(confidence < 임계)이어도 보류되지 않는다 (static 게이트 없음)")
    void starCell_isNotBlockedByLowStaticConfidence() {
        // Arrange — confidence 0.55 (< 저확신 임계 0.65) 신호
        pipeline.onSignal(
                new SignalObservation(
                        "005930",
                        "D20",
                        LocalDate.of(2026, 9, 28),
                        "trace-signal",
                        "STRONG_BUY",
                        bd("0.051"),
                        bd("0.550")));

        // Act
        tick(LocalTime.of(10, 0));
        tick(LocalTime.of(10, 15));

        // Assert
        AlertDecision last = decisions.getLast();
        assertThat(last.isCandidate()).isTrue();
        assertThat(last.tier()).isEqualTo(1);
        assertThat(last.confidence()).isEqualByComparingTo("0.550");
    }
}
