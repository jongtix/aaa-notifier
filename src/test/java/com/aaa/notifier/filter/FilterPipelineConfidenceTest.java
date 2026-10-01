package com.aaa.notifier.filter;

import static com.aaa.notifier.filter.FilterTestFixtures.bd;
import static com.aaa.notifier.filter.FilterTestFixtures.domesticReference;
import static com.aaa.notifier.filter.FilterTestFixtures.domesticTick;
import static com.aaa.notifier.filter.FilterTestFixtures.holderOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.notifier.stream.SignalObservation;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("FilterPipeline — confidence 회전 윈도·방향성 약화 강등·저확신 표시 (REQ-051~053, AC-17~19)")
class FilterPipelineConfidenceTest {

    private static final LocalDate FIRST_SIGNAL_DAY = LocalDate.of(2026, 9, 21);

    private InMemoryFilterStateStore store;
    private List<AlertDecision> decisions;
    private long accumulated = 100_000L;

    @BeforeEach
    void setUp() {
        store = new InMemoryFilterStateStore();
        decisions = new ArrayList<>();
    }

    private FilterPipeline pipeline(Grade bandGrade) {
        return FilterPipelines.create(
                holderOf(
                        domesticReference(
                                Map.of("D20", FilterTestFixtures.uniformBands(bandGrade)))),
                store,
                decisions::add,
                new SimpleMeterRegistry(),
                FilterTestFixtures.DOMESTIC_SESSION_CLOCK);
    }

    private static SignalObservation signal(int dayOffset, String confidence) {
        return new SignalObservation(
                "005930",
                "D20",
                FIRST_SIGNAL_DAY.plusDays(dayOffset),
                "trace-signal-" + dayOffset,
                "BUY",
                bd("0.030"),
                bd(confidence));
    }

    private void feedSignals(FilterPipeline pipeline, String... confidences) {
        for (int i = 0; i < confidences.length; i++) {
            pipeline.onSignal(signal(i, confidences[i]));
        }
    }

    /**
     * 10:00:00 체결로 교차한 뒤 그 칸의 유지 시간이 지난 체결을 한 건 더 보낸다 — 유지 확증을 통과시켜 쿨다운·confidence 단계까지 보낸다
     * (SPEC-NOTIFIER-FILTER-002).
     */
    private void crossAndDwell(FilterPipeline pipeline, Duration dwell) {
        LocalTime crossing = LocalTime.of(10, 0);
        for (LocalTime time : List.of(crossing, crossing.plus(dwell))) {
            accumulated += 1_000L;
            pipeline.onTradeTick(domesticTick("30000", time, accumulated));
        }
    }

    @Nested
    @DisplayName("AC-17 — filter:confidence 회전 윈도 (REQ-051)")
    class RollingWindow {

        @Test
        @DisplayName("6번째 신호가 오면 가장 오래된 값이 빠지고 최근 5개만 남는다")
        void sixthSignal_trimsOldest() {
            // Arrange
            FilterPipeline pipeline = pipeline(Grade.BUY);

            // Act
            feedSignals(pipeline, "0.60", "0.61", "0.62", "0.63", "0.64", "0.65");

            // Assert
            assertThat(store.confidences("005930", "D20"))
                    .extracting(BigDecimal::toPlainString)
                    .containsExactly("0.61", "0.62", "0.63", "0.64", "0.65");
        }

        @Test
        @DisplayName("AC-21 — onSignal은 상태만 갱신하고 결정 싱크를 호출하지 않는다 (틱만이 구동원)")
        void onSignal_neverCallsDecisionSink() {
            // Arrange — 유효 등급과 다른 등급의 신호가 와도
            FilterPipeline pipeline = pipeline(Grade.BUY);
            store.setGrade("005930", "D20", Grade.HOLD, Duration.ofHours(1));

            // Act
            feedSignals(pipeline, "0.80", "0.75", "0.70", "0.65", "0.60", "0.55");

            // Assert
            assertThat(decisions).isEmpty();
            assertThat(store.gradeOf("005930", "D20")).isEqualTo(Grade.HOLD);
        }

        @Test
        @DisplayName("같은 거래일 신호의 재전달은 윈도에 두 번 쌓이지 않는다")
        void redeliveredSignal_isNotAppendedTwice() {
            // Arrange
            FilterPipeline pipeline = pipeline(Grade.BUY);

            // Act
            pipeline.onSignal(signal(0, "0.70"));
            pipeline.onSignal(signal(0, "0.70"));

            // Assert
            assertThat(store.confidences("005930", "D20")).hasSize(1);
        }
    }

    @Nested
    @DisplayName("AC-18 — 약화 추세 강등은 Tier 구분 없이 균일 적용 (REQ-052)")
    class WeakeningDemotion {

        @Test
        @DisplayName("Tier 2 후보(HOLD→BUY)는 연속 하락 추세에서 강등·억제된다")
        void tierTwoCandidate_isDemoted() {
            // Arrange
            FilterPipeline pipeline = pipeline(Grade.BUY);
            feedSignals(pipeline, "0.80", "0.75", "0.70", "0.65", "0.60");
            store.setGrade("005930", "D20", Grade.HOLD, Duration.ofHours(1));

            // Act
            crossAndDwell(pipeline, FilterPipelines.DWELL.holdEntry());

            // Assert
            assertThat(decisions.getLast())
                    .extracting(AlertDecision::tier, AlertDecision::suppressionReason)
                    .containsExactly(2, SuppressionReason.CONFIDENCE_WEAKENING_DEMOTED);
        }

        @Test
        @DisplayName("E5 — 유지 시간을 채운 Tier 1 후보(BUY→STRONG_BUY, STRONG 도달)도 예외 없이 강등·억제로 종결된다")
        void tierOneCandidate_isAlsoDemoted() {
            // Arrange
            FilterPipeline pipeline = pipeline(Grade.STRONG_BUY);
            feedSignals(pipeline, "0.80", "0.75", "0.70", "0.65", "0.60");
            store.setGrade("005930", "D20", Grade.BUY, Duration.ofHours(1));

            // Act
            crossAndDwell(pipeline, FilterPipelines.DWELL.strengthUp());

            // Assert — 교차 직후의 유지 대기 결정을 빼면 종결 결정은 강등 1건이다
            assertThat(
                            decisions.stream()
                                    .filter(
                                            decision ->
                                                    decision.suppressionReason()
                                                            != SuppressionReason.DWELL_PENDING))
                    .singleElement()
                    .extracting(AlertDecision::tier, AlertDecision::suppressionReason)
                    .containsExactly(1, SuppressionReason.CONFIDENCE_WEAKENING_DEMOTED);
            assertThat(store.cooldowns).as("강등된 후보는 쿨다운을 걸지 않는다").isEmpty();
        }

        @Test
        @DisplayName("E5 — 윈도가 N개 미만이면 추세 판단 불가로 보고 강등하지 않는다")
        void shortWindow_isNotTreatedAsWeakening() {
            // Arrange
            FilterPipeline pipeline = pipeline(Grade.STRONG_BUY);
            feedSignals(pipeline, "0.80", "0.60");
            store.setGrade("005930", "D20", Grade.BUY, Duration.ofHours(1));

            // Act
            crossAndDwell(pipeline, FilterPipelines.DWELL.strengthUp());

            // Assert
            assertThat(decisions.getLast().isCandidate()).isTrue();
        }

        @Test
        @DisplayName("하락 폭이 임계(0.05) 미만이면 약화 추세가 아니다")
        void smallDecline_isNotWeakening() {
            // Arrange
            FilterPipeline pipeline = pipeline(Grade.STRONG_BUY);
            feedSignals(pipeline, "0.710", "0.700", "0.690", "0.680", "0.670");
            store.setGrade("005930", "D20", Grade.BUY, Duration.ofHours(1));

            // Act
            crossAndDwell(pipeline, FilterPipelines.DWELL.strengthUp());

            // Assert
            assertThat(decisions.getLast().isCandidate()).isTrue();
        }

        @Test
        @DisplayName("중간에 반등이 있으면 연속 하락 추세가 아니다")
        void reboundBreaksTrend() {
            // Arrange
            FilterPipeline pipeline = pipeline(Grade.STRONG_BUY);
            feedSignals(pipeline, "0.80", "0.70", "0.72", "0.65", "0.60");
            store.setGrade("005930", "D20", Grade.BUY, Duration.ofHours(1));

            // Act
            crossAndDwell(pipeline, FilterPipelines.DWELL.strengthUp());

            // Assert
            assertThat(decisions.getLast().isCandidate()).isTrue();
        }
    }

    @Test
    @DisplayName("AC-19 — confidence가 저확신 임계 미만이면 표시 플래그만 싣고 발송을 막지 않는다")
    void lowConfidence_isFlaggedNotBlocked() {
        // Arrange
        FilterPipeline pipeline = pipeline(Grade.STRONG_BUY);
        pipeline.onSignal(signal(0, "0.600"));
        store.setGrade("005930", "D20", Grade.BUY, Duration.ofHours(1));

        // Act
        crossAndDwell(pipeline, FilterPipelines.DWELL.strengthUp());

        // Assert
        assertThat(decisions.getLast())
                .extracting(AlertDecision::lowConfidenceFlag, AlertDecision::suppressionReason)
                .containsExactly(true, null);
    }

    @Test
    @DisplayName("confidence가 임계 이상이면 저확신 플래그가 없다")
    void normalConfidence_isNotFlagged() {
        // Arrange
        FilterPipeline pipeline = pipeline(Grade.STRONG_BUY);
        pipeline.onSignal(signal(0, "0.650"));
        store.setGrade("005930", "D20", Grade.BUY, Duration.ofHours(1));

        // Act
        crossAndDwell(pipeline, FilterPipelines.DWELL.strengthUp());

        // Assert — 발송 후보임을 먼저 확인한다(대기 억제 결정을 검사하며 조용히 통과하지 않도록, N6)
        assertThat(decisions.getLast())
                .extracting(AlertDecision::isCandidate, AlertDecision::lowConfidenceFlag)
                .containsExactly(true, false);
    }
}
