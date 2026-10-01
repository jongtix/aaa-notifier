package com.aaa.notifier.filter;

import static com.aaa.notifier.filter.FilterTestFixtures.bd;
import static com.aaa.notifier.filter.FilterTestFixtures.domesticReference;
import static com.aaa.notifier.filter.FilterTestFixtures.domesticTick;
import static com.aaa.notifier.filter.FilterTestFixtures.holderOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.notifier.stream.Market;
import com.aaa.notifier.stream.QuoteObservation;
import com.aaa.notifier.stream.SignalObservation;
import com.aaa.notifier.stream.TradeTickObservation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("FilterPipeline — 밴드 판정·이원 경계·horizon 독립 (REQ-011~015, AC-3~AC-6)")
class FilterPipelineBandTest {

    private static final LocalTime TEN_THIRTY = LocalTime.of(10, 30);

    private InMemoryFilterStateStore store;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        store = new InMemoryFilterStateStore();
        registry = new SimpleMeterRegistry();
    }

    private FilterPipeline pipeline(ReferenceDataHolder holder) {
        return FilterPipelines.create(
                holder, store, decision -> {}, registry, FilterTestFixtures.DOMESTIC_SESSION_CLOCK);
    }

    private double stageCount(String stage, String outcome) {
        Counter counter =
                registry.find(FilterMetrics.STAGE_COUNTER)
                        .tag("stage", stage)
                        .tag("outcome", outcome)
                        .counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Nested
    @DisplayName("AC-3 — 이원 경계 일치 시 전환, 불일치 시 데드존 유지")
    class DualBoundary {

        @Test
        @DisplayName("양 파티션 일치(30,600)면 전환, 이어 불일치(30,200)면 BUY로 되돌아가지 않는다")
        void agreementSwitches_thenDeadZoneHolds() {
            // Arrange
            FilterPipeline pipeline =
                    pipeline(
                            holderOf(
                                    domesticReference(
                                            Map.of(
                                                    "D20",
                                                    FilterTestFixtures.buyStrongBuyBands()))));
            store.setGrade("005930", "D20", Grade.BUY, Duration.ofHours(1));

            // Act & Assert
            pipeline.onTradeTick(domesticTick("30600", TEN_THIRTY, 100));
            assertThat(store.gradeOf("005930", "D20")).isEqualTo(Grade.STRONG_BUY);

            pipeline.onTradeTick(domesticTick("30200", TEN_THIRTY, 200));
            assertThat(store.gradeOf("005930", "D20")).isEqualTo(Grade.STRONG_BUY);
            assertThat(stageCount("band", "dead_zone")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("데드존은 'HOLD로 전환'이 아니라 '현재 등급 유지'다")
        void deadZone_isNotHold() {
            // Arrange
            FilterPipeline pipeline =
                    pipeline(
                            holderOf(
                                    domesticReference(
                                            Map.of(
                                                    "D20",
                                                    FilterTestFixtures.buyStrongBuyBands()))));
            store.setGrade("005930", "D20", Grade.BUY, Duration.ofHours(1));

            // Act
            pipeline.onTradeTick(domesticTick("30200", TEN_THIRTY, 100));

            // Assert
            assertThat(store.gradeOf("005930", "D20")).isEqualTo(Grade.BUY);
        }

        @Test
        @DisplayName("유효 등급이 없으면 전일 종가 DEMOTE 등급으로 초기화한 뒤 판정한다")
        void missingGrade_isInitializedFromDemote() {
            // Arrange
            FilterPipeline pipeline =
                    pipeline(
                            holderOf(
                                    domesticReference(
                                            Map.of(
                                                    "D20",
                                                    FilterTestFixtures.buyStrongBuyBands()))));

            // Act — 30,200은 데드존이므로 초기값(DEMOTE(30,000)=STRONG_BUY)이 그대로 남는다
            pipeline.onTradeTick(domesticTick("30200", TEN_THIRTY, 100));

            // Assert
            assertThat(store.gradeOf("005930", "D20")).isEqualTo(Grade.STRONG_BUY);
        }
    }

    @Test
    @DisplayName("AC-4 — 같은 체결로 D20·D60이 서로 간섭 없이 상반 방향으로 전환된다")
    void horizonsAreIndependent() {
        // Arrange
        FilterPipeline pipeline =
                pipeline(
                        holderOf(
                                domesticReference(
                                        Map.of(
                                                "D20",
                                                FilterTestFixtures.uniformBands(Grade.BUY),
                                                "D60",
                                                FilterTestFixtures.uniformBands(Grade.SELL)))));
        store.setGrade("005930", "D20", Grade.HOLD, Duration.ofHours(1));
        store.setGrade("005930", "D60", Grade.HOLD, Duration.ofHours(1));

        // Act
        pipeline.onTradeTick(domesticTick("30000", TEN_THIRTY, 100));

        // Assert
        assertThat(store.gradeOf("005930", "D20")).isEqualTo(Grade.BUY);
        assertThat(store.gradeOf("005930", "D60")).isEqualTo(Grade.SELL);
    }

    @Test
    @DisplayName("AC-5 — 그리드 밖 해외 체결은 최외곽 밴드로 클램프하고 계측한다")
    void outOfGridOverseasPrice_isClamped() {
        // Arrange — 전일 종가 100, 그리드 [78.5, 121.5]
        StockReference aapl =
                new StockReference(
                        9L,
                        "AAPL",
                        Market.OVERSEAS,
                        FilterTestFixtures.PREV_DAY,
                        bd("100.0000"),
                        bd("1000000"),
                        bd("2"),
                        Map.of(
                                "D20",
                                new HorizonBands(
                                        FilterTestFixtures.partition(
                                                FilterTestFixtures.band(
                                                        "78.5000", "99.9000", Grade.HOLD),
                                                FilterTestFixtures.band(
                                                        "100.0000", "121.5000", Grade.BUY)),
                                        FilterTestFixtures.partition(
                                                FilterTestFixtures.band(
                                                        "78.5000", "99.9000", Grade.HOLD),
                                                FilterTestFixtures.band(
                                                        "100.0000", "121.5000", Grade.BUY)))));
        FilterPipeline pipeline =
                FilterPipelines.create(
                        holderOf(aapl),
                        store,
                        decision -> {},
                        registry,
                        FilterTestFixtures.OVERSEAS_SESSION_CLOCK);
        store.setGrade("AAPL", "D20", Grade.HOLD, Duration.ofHours(1));

        // Act
        pipeline.onTradeTick(
                new TradeTickObservation(
                        "AAPL",
                        "DNASAAPL",
                        Market.OVERSEAS,
                        bd("130.0000"),
                        5L,
                        500L,
                        LocalTime.of(23, 0),
                        4,
                        "trace-aapl"));

        // Assert
        assertThat(store.gradeOf("AAPL", "D20")).isEqualTo(Grade.BUY);
        assertThat(stageCount("band", "clamped")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("AC-6 — price.scale() ≠ priceScale인 해외 체결은 밴드 판정에서 제외하고 계측한다")
    void scaleMismatch_isSkipped() {
        // Arrange
        StockReference aapl =
                new StockReference(
                        9L,
                        "AAPL",
                        Market.OVERSEAS,
                        FilterTestFixtures.PREV_DAY,
                        bd("100.0000"),
                        bd("1000000"),
                        bd("2"),
                        Map.of("D20", FilterTestFixtures.uniformBands(Grade.BUY)));
        FilterPipeline pipeline =
                FilterPipelines.create(
                        holderOf(aapl),
                        store,
                        decision -> {},
                        registry,
                        FilterTestFixtures.OVERSEAS_SESSION_CLOCK);
        store.setGrade("AAPL", "D20", Grade.HOLD, Duration.ofHours(1));

        // Act — 비스케일 정수(2984700, scale 0)인데 ZDIV=4
        pipeline.onTradeTick(
                new TradeTickObservation(
                        "AAPL",
                        "DNASAAPL",
                        Market.OVERSEAS,
                        bd("2984700"),
                        5L,
                        500L,
                        LocalTime.of(23, 0),
                        4,
                        "trace-aapl"));

        // Assert
        assertThat(store.gradeOf("AAPL", "D20")).isEqualTo(Grade.HOLD);
        assertThat(stageCount("band", "skipped_scale_mismatch")).isEqualTo(1.0);
    }

    @Nested
    @DisplayName("FILTER-002 — 후보의 전환 체결 시각 (REQ-011)")
    class TransitionTradeTime {

        private final Instant tenThirty =
                ZonedDateTime.of(2026, 9, 29, 10, 30, 0, 0, MarketSession.KST).toInstant();

        @Test
        @DisplayName("교차한 체결의 체결 시각이 새 후보의 전환 체결 시각으로 기록된다")
        void crossing_recordsTradeTimeAsSince() {
            // Arrange
            FilterPipeline pipeline =
                    pipeline(
                            holderOf(
                                    domesticReference(
                                            Map.of(
                                                    "D20",
                                                    FilterTestFixtures.uniformBands(Grade.BUY)))));
            store.setGrade("005930", "D20", Grade.HOLD, Duration.ofHours(1));

            // Act
            pipeline.onTradeTick(domesticTick("30000", TEN_THIRTY, 100_000L));

            // Assert
            assertThat(store.pending("005930", "D20").orElseThrow().since()).isEqualTo(tenThirty);
        }

        @Test
        @DisplayName("전환 체결 시각이 없는 기존 후보는 처음 평가하는 체결의 체결 시각을 기록한다")
        void legacyCandidateWithoutSince_recordsFirstEvaluatedTradeTime() {
            // Arrange — 배포 전 형식(전환 체결 시각 없음)의 후보와 그 후보 등급의 유효 등급
            FilterPipeline pipeline =
                    pipeline(
                            holderOf(
                                    domesticReference(
                                            Map.of(
                                                    "D20",
                                                    FilterTestFixtures.uniformBands(Grade.BUY)))));
            store.setGrade("005930", "D20", Grade.BUY, Duration.ofHours(1));
            store.savePending(
                    "005930",
                    "D20",
                    new PendingTransition(Grade.HOLD, Grade.BUY, null, null),
                    Duration.ofHours(1));

            // Act
            pipeline.onTradeTick(domesticTick("30000", TEN_THIRTY, 100_000L));

            // Assert
            assertThat(store.pending("005930", "D20").orElseThrow().since()).isEqualTo(tenThirty);
        }
    }

    @Test
    @DisplayName("감시 대상이 아닌(적재되지 않은) 종목의 체결은 무시한다")
    void unknownSymbol_isIgnored() {
        // Arrange
        FilterPipeline pipeline = pipeline(new ReferenceDataHolder());

        // Act
        pipeline.onTradeTick(domesticTick("30600", TEN_THIRTY, 100));

        // Assert
        assertThat(store.grades).isEmpty();
    }

    @Test
    @DisplayName("정규장 마감 이후 체결은 판정하지 않는다")
    void afterClose_isIgnored() {
        // Arrange
        FilterPipeline pipeline =
                FilterPipelines.create(
                        holderOf(
                                domesticReference(
                                        Map.of("D20", FilterTestFixtures.uniformBands(Grade.BUY)))),
                        store,
                        decision -> {},
                        registry,
                        FilterTestFixtures.OVERSEAS_SESSION_CLOCK); // 23:00 KST = 국내 마감 후

        // Act
        pipeline.onTradeTick(domesticTick("30000", LocalTime.of(23, 0), 100));

        // Assert
        assertThat(store.grades).isEmpty();
    }

    @Nested
    @DisplayName("신호·호가 수신 경로 (REQ-063 후단)")
    class NonTickPaths {

        @Test
        @DisplayName("onSignal은 filter:signal 스냅샷만 갱신하고 유효 등급을 건드리지 않는다")
        void onSignal_updatesSignalSnapshotOnly() {
            // Arrange
            FilterPipeline pipeline = pipeline(new ReferenceDataHolder());
            SignalObservation signal =
                    new SignalObservation(
                            "005930",
                            "D20",
                            LocalDate.of(2026, 9, 28),
                            "trace-signal",
                            "BUY",
                            bd("0.043"),
                            bd("0.712"));

            // Act
            pipeline.onSignal(signal);

            // Assert
            SignalSnapshot snapshot = store.signal("005930", "D20").orElseThrow();
            assertThat(snapshot.signalClass()).isEqualTo("BUY");
            assertThat(snapshot.confidence()).isEqualByComparingTo("0.712");
            assertThat(store.grades).isEmpty();
        }

        @Test
        @DisplayName("onQuote는 무동작이다 — 호가 필드를 해석하지 않는다")
        void onQuote_isNoOp() {
            // Arrange
            FilterPipeline pipeline = pipeline(new ReferenceDataHolder());

            // Act
            pipeline.onQuote(
                    new QuoteObservation(
                            "005930", "005930", Market.DOMESTIC, "H0STASP0", "raw^record", "t"));

            // Assert
            assertThat(store.grades).isEmpty();
            assertThat(store.signals).isEmpty();
        }
    }
}
