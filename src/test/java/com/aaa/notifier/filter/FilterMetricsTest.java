package com.aaa.notifier.filter;

import static com.aaa.notifier.filter.FilterTestFixtures.domesticReference;
import static com.aaa.notifier.filter.FilterTestFixtures.domesticTick;
import static com.aaa.notifier.filter.FilterTestFixtures.holderOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.notifier.stream.Market;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FilterMetrics — Prometheus 노출과 warm-start 게이지 (REQ-071, AC-22)")
class FilterMetricsTest {

    @Test
    @DisplayName("AC-22 — 첫 틱 이전에도 notifier_filter_confirm_pending 게이지가 0으로 노출된다")
    void pendingGauge_isRegisteredBeforeFirstEvent() {
        // Arrange
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        // Act
        FilterMetrics.create(registry);

        // Assert
        assertThat(registry.scrape())
                .contains("notifier_filter_confirm_pending 0.0")
                .contains("notifier_filter_reference_stocks 0.0");
    }

    @Test
    @DisplayName("확증 대기 후보가 생기면 게이지가 오르고 종결되면 내려간다")
    void pendingGauge_tracksOpenCandidates() {
        // Arrange
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        InMemoryFilterStateStore store = new InMemoryFilterStateStore();
        FilterPipeline pipeline =
                FilterPipelines.create(
                        holderOf(
                                domesticReference(
                                        Map.of("D20", FilterTestFixtures.uniformBands(Grade.BUY)))),
                        store,
                        decision -> {},
                        registry,
                        FilterTestFixtures.DOMESTIC_SESSION_CLOCK);
        store.setGrade("005930", "D20", Grade.HOLD, Duration.ofHours(1));

        // Act & Assert — HOLD→BUY 확증 5회 중 1회차: 대기
        pipeline.onTradeTick(domesticTick("30000", LocalTime.of(10, 0), 100_000L));
        assertThat(registry.get("notifier.filter.confirm.pending").gauge().value()).isEqualTo(1.0);

        for (long volume = 101_000L; volume <= 104_000L; volume += 1_000L) {
            pipeline.onTradeTick(domesticTick("30000", LocalTime.of(10, 0), volume));
        }
        assertThat(registry.get("notifier.filter.confirm.pending").gauge().value()).isZero();
    }

    @Test
    @DisplayName("억제 이벤트가 나면 stage별 카운터가 Prometheus에 노출된다")
    void stageCounter_isExposedAfterSuppression() {
        // Arrange
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        InMemoryFilterStateStore store = new InMemoryFilterStateStore();
        FilterPipeline pipeline =
                FilterPipelines.create(
                        holderOf(
                                domesticReference(
                                        Map.of("D20", FilterTestFixtures.uniformBands(Grade.BUY)))),
                        store,
                        decision -> {},
                        registry,
                        FilterTestFixtures.DOMESTIC_SESSION_CLOCK);
        store.setGrade("005930", "D20", Grade.HOLD, Duration.ofHours(1));

        // Act — 거래량 미달
        pipeline.onTradeTick(domesticTick("30000", LocalTime.of(10, 0), 1L));

        // Assert
        assertThat(registry.scrape())
                .contains(
                        "notifier_filter_stage_total{outcome=\"block\",stage=\"guard_volume\"} 1.0");
    }

    @Test
    @DisplayName("FILTER-002 AC-14 — 유지 시간 단계의 통과·차단·무효화가 stage=\"dwell\" 태그로 노출된다 (REQ-015)")
    void dwellStage_isExposedWithVoidedOutcome() {
        // Arrange
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        FilterMetrics metrics = FilterMetrics.create(registry);

        // Act
        metrics.stage(FilterMetrics.Stage.DWELL, FilterMetrics.Outcome.PASS);
        metrics.stage(FilterMetrics.Stage.DWELL, FilterMetrics.Outcome.BLOCK);
        metrics.stage(FilterMetrics.Stage.DWELL, FilterMetrics.Outcome.VOIDED);

        // Assert
        assertThat(registry.scrape())
                .contains("notifier_filter_stage_total{outcome=\"pass\",stage=\"dwell\"} 1.0")
                .contains("notifier_filter_stage_total{outcome=\"block\",stage=\"dwell\"} 1.0")
                .contains("notifier_filter_stage_total{outcome=\"voided\",stage=\"dwell\"} 1.0");
    }

    @Test
    @DisplayName(
            "FILTER-002 AC-14 — 유지 시간 억제 결정은 suppression_reason=\"dwell_pending\" 태그로 센다 (REQ-013)")
    void dwellPendingDecision_isTaggedDwellPending() {
        // Arrange
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        FilterMetrics metrics = FilterMetrics.create(registry);

        // Act
        metrics.decision(
                new AlertDecision(
                        1L,
                        "005930",
                        Market.DOMESTIC,
                        "D20",
                        Grade.HOLD,
                        Grade.BUY,
                        2,
                        TransitionType.HOLD_ENTRY,
                        null,
                        null,
                        false,
                        new BigDecimal("30000"),
                        new BigDecimal("30000"),
                        LocalDate.of(2026, 9, 29),
                        SuppressionReason.DWELL_PENDING,
                        "trace"));

        // Assert
        assertThat(registry.scrape())
                .contains(
                        "notifier_filter_decision_total{outcome=\"suppressed\",suppression_reason=\"dwell_pending\"} 1.0");
    }

    @Test
    @DisplayName("개장 리셋은 그 시장의 대기 후보 수만 비운다")
    void resetPending_isPerMarket() {
        // Arrange
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        FilterMetrics metrics = FilterMetrics.create(registry);
        metrics.pendingOpened(Market.DOMESTIC, "005930", "D20");
        metrics.pendingOpened(Market.OVERSEAS, "AAPL", "D20");

        // Act
        metrics.resetPending(Market.DOMESTIC);

        // Assert
        assertThat(registry.get("notifier.filter.confirm.pending").gauge().value()).isEqualTo(1.0);
    }
}
