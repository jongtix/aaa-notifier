package com.aaa.notifier.filter;

import static com.aaa.notifier.filter.FilterTestFixtures.band;
import static com.aaa.notifier.filter.FilterTestFixtures.bd;
import static com.aaa.notifier.filter.FilterTestFixtures.holderOf;
import static com.aaa.notifier.filter.FilterTestFixtures.partition;
import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.notifier.stream.Market;
import com.aaa.notifier.stream.TradeTickObservation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 등급 유지 확증 (SPEC-NOTIFIER-FILTER-002) — 전환 유형별 유지 시간, 기준 등급, 억제 방출 이력.
 *
 * <p>시간 경과는 체결 관측치의 체결 시각으로 만든다 — 유지 시간을 줄이지 않고(REQ-012) sleep도 쓰지 않는다. 시계는 고정이며 장 마감 TTL과 체결 시각의
 * 날짜만 이 시계로 정해진다.
 */
@DisplayName("FilterPipeline — 등급 유지 확증 (SPEC-NOTIFIER-FILTER-002)")
class FilterPipelineDwellTest {

    private static final String HORIZON = "D20";

    private static final String SYMBOL = "005930";

    private static final LocalTime TEN = LocalTime.of(10, 0);

    /** 거래량 가드가 언제나 통과하는 누적 거래량 시작값(기대치 상한 = 1,000,000 × 0.3). */
    private static final long START_VOLUME = 1_000_000L;

    /** 2026-09-29(화) 15:00 KST — 국내 마감 30분 전. */
    private static final Clock DOMESTIC_CLOSING_CLOCK = kstClock(2026, 9, 29, 15, 0);

    /** 등급별 대표 체결가 — {@link #ladderBands()}에서 양 파티션이 모두 그 등급을 가리킨다. */
    private static final Map<Grade, String> PRICE =
            Map.of(
                    Grade.STRONG_SELL, "21000",
                    Grade.SELL, "23000",
                    Grade.HOLD, "25000",
                    Grade.BUY, "27000",
                    Grade.STRONG_BUY, "29000");

    private InMemoryFilterStateStore store;
    private List<AlertDecision> decisions;
    private List<LocalTime> decisionTimes;
    private SimpleMeterRegistry registry;
    private long accumulated;
    private LocalTime currentTime;

    @BeforeEach
    void setUp() {
        store = new InMemoryFilterStateStore();
        decisions = new ArrayList<>();
        decisionTimes = new ArrayList<>();
        registry = new SimpleMeterRegistry();
        accumulated = START_VOLUME;
    }

    private static Clock kstClock(int year, int month, int day, int hour, int minute) {
        return Clock.fixed(
                ZonedDateTime.of(year, month, day, hour, minute, 0, 0, MarketSession.KST)
                        .toInstant(),
                MarketSession.KST);
    }

    /** 평균 거래량 1,000,000·ATR 1,000,000 — 거래량·ATR 가드가 판정을 가리지 않는 종목. */
    private static StockReference reference(
            String symbol, Market market, String prevClose, HorizonBands bands) {
        return new StockReference(
                1L,
                symbol,
                market,
                FilterTestFixtures.PREV_DAY,
                bd(prevClose),
                bd("1000000"),
                bd("1000000"),
                Map.of(HORIZON, bands));
    }

    /** 다섯 등급이 가격 순으로 늘어선 밴드(데드존 없음). 전일 종가 25,000은 HOLD 영역이다. */
    private static StockReference ladder(Market market) {
        HorizonBands bands =
                new HorizonBands(
                        partition(
                                band("20000", "21990", Grade.STRONG_SELL),
                                band("22000", "23990", Grade.SELL),
                                band("24000", "25990", Grade.HOLD),
                                band("26000", "27990", Grade.BUY),
                                band("28000", "30000", Grade.STRONG_BUY)),
                        partition(
                                band("20000", "21990", Grade.STRONG_SELL),
                                band("22000", "23990", Grade.SELL),
                                band("24000", "25990", Grade.HOLD),
                                band("26000", "27990", Grade.BUY),
                                band("28000", "30000", Grade.STRONG_BUY)));
        return reference(SYMBOL, market, "25000", bands);
    }

    /**
     * HOLD ↔ BUY 데드존 밴드 — PROMOTE 경계 25,500 / DEMOTE 경계 24,800. 25,600 = 양쪽 BUY, 24,000 = 양쪽 HOLD,
     * 25,200 = PROMOTE HOLD · DEMOTE BUY(데드존).
     */
    static StockReference deadZoneReference() {
        return reference(
                SYMBOL,
                Market.DOMESTIC,
                "24000",
                new HorizonBands(
                        partition(
                                band("20000", "25490", Grade.HOLD),
                                band("25500", "30000", Grade.BUY)),
                        partition(
                                band("20000", "24790", Grade.HOLD),
                                band("24800", "30000", Grade.BUY))));
    }

    /** 두 파티션이 같은 단일 경계를 갖는 밴드(데드존 폭 0) — 경계 미만은 {@code below}, 경계 이상은 {@code above}. */
    private static HorizonBands zeroWidthBands(
            String low, String boundary, String high, Grade below, Grade above) {
        String belowHigh = bd(boundary).subtract(bd("1")).toPlainString();
        return new HorizonBands(
                partition(band(low, belowHigh, below), band(boundary, high, above)),
                partition(band(low, belowHigh, below), band(boundary, high, above)));
    }

    private FilterPipeline pipeline(StockReference reference, Clock clock) {
        return FilterPipelines.create(
                holderOf(reference),
                store,
                decision -> {
                    decisions.add(decision);
                    decisionTimes.add(currentTime);
                },
                registry,
                clock);
    }

    private FilterPipeline domesticPipeline() {
        return pipeline(ladder(Market.DOMESTIC), FilterTestFixtures.DOMESTIC_SESSION_CLOCK);
    }

    private void tick(
            FilterPipeline pipeline, Market market, String symbol, String price, LocalTime time) {
        accumulated += 1_000L;
        currentTime = time;
        pipeline.onTradeTick(
                new TradeTickObservation(
                        symbol,
                        symbol,
                        market,
                        bd(price),
                        10L,
                        accumulated,
                        time,
                        null,
                        "trace-" + symbol + "-" + accumulated));
    }

    private void tick(FilterPipeline pipeline, String symbol, String price, LocalTime time) {
        tick(pipeline, Market.DOMESTIC, symbol, price, time);
    }

    private void tick(FilterPipeline pipeline, Grade grade, LocalTime time) {
        tick(pipeline, SYMBOL, PRICE.get(grade), time);
    }

    /**
     * {@code from}부터 {@code to}까지(양끝 포함) 10초 간격으로 {@code price} 체결을 보낸다. 자정을 넘기는 구간은 두 번에 나눠 부른다
     * ({@link LocalTime}은 자정에서 되감기므로 횟수로 돈다).
     */
    private void ticksEvery10s(
            FilterPipeline pipeline, Market market, String price, LocalTime from, LocalTime to) {
        long steps = Duration.between(from, to).toSeconds() / 10;
        for (long step = 0; step <= steps; step++) {
            tick(pipeline, market, SYMBOL, price, from.plusSeconds(10 * step));
        }
    }

    private void ticksEvery10s(FilterPipeline pipeline, Grade grade, LocalTime from, LocalTime to) {
        ticksEvery10s(pipeline, Market.DOMESTIC, PRICE.get(grade), from, to);
    }

    private long candidates() {
        return decisions.stream().filter(AlertDecision::isCandidate).count();
    }

    /** 발송 후보 결정이 나온 체결의 체결 시각들. */
    private List<LocalTime> candidateTimes() {
        List<LocalTime> times = new ArrayList<>();
        for (int i = 0; i < decisions.size(); i++) {
            if (decisions.get(i).isCandidate()) {
                times.add(decisionTimes.get(i));
            }
        }
        return times;
    }

    private long suppressedBy(SuppressionReason reason) {
        return decisions.stream()
                .filter(decision -> decision.suppressionReason() == reason)
                .count();
    }

    private double stageCount(String stage, String outcome) {
        Counter counter =
                registry.find(FilterMetrics.STAGE_COUNTER)
                        .tag("stage", stage)
                        .tag("outcome", outcome)
                        .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static ZonedDateTime kst(LocalDate date, LocalTime time) {
        return ZonedDateTime.of(date, time, MarketSession.KST);
    }

    @Nested
    @DisplayName("유지 확증의 의미 (REQ-001/005/006/007)")
    class Meaning {

        @Test
        @DisplayName("AC-01 — 유지 시간 이상 유지된 전환은 유지 시간을 채운 체결에서 발송 후보 정확히 1건, 체결 건수는 쓰이지 않는다")
        void dwellSatisfied_emitsExactlyOneCandidate() {
            // Arrange — 기준 HOLD, HOLD→BUY(유지 10분)
            FilterPipeline pipeline = domesticPipeline();
            store.setGrade(SYMBOL, HORIZON, Grade.HOLD, Duration.ofHours(1));

            // Act — 10초마다 체결 2건씩(10:09:50까지 120건 > 60건), 10:10:00은 다른 체결가, 10:11:00까지 이어짐
            for (LocalTime time = TEN;
                    time.isBefore(LocalTime.of(10, 10));
                    time = time.plusSeconds(10)) {
                tick(pipeline, Grade.BUY, time);
                tick(pipeline, Grade.BUY, time);
            }
            long candidatesBeforeDwell = candidates();
            tick(pipeline, SYMBOL, "27500", LocalTime.of(10, 10));
            ticksEvery10s(pipeline, Grade.BUY, LocalTime.of(10, 10, 10), LocalTime.of(10, 11));

            // Assert
            assertThat(candidatesBeforeDwell).as("10:09:50까지 120건이 있어도 발송 후보 없음").isZero();
            assertThat(candidateTimes()).containsExactly(LocalTime.of(10, 10));
            assertThat(decisions.stream().filter(AlertDecision::isCandidate))
                    .singleElement()
                    .extracting(
                            AlertDecision::fromGrade,
                            AlertDecision::toGrade,
                            AlertDecision::tier,
                            AlertDecision::transitionType)
                    .containsExactly(Grade.HOLD, Grade.BUY, 2, TransitionType.HOLD_ENTRY);
            assertThat(decisions.getLast().triggerPrice()).isEqualByComparingTo("27500");
        }

        @Test
        @DisplayName("AC-05 — 유지 시간이 찬 뒤 체결이 없으면 결정 없이 기다리고, 다음 일치 체결이 그 체결가로 판정한다")
        void noTradeAfterDwell_waitsForNextTrade() {
            // Arrange
            FilterPipeline pipeline = domesticPipeline();
            store.setGrade(SYMBOL, HORIZON, Grade.HOLD, Duration.ofHours(1));
            ticksEvery10s(pipeline, Grade.BUY, TEN, LocalTime.of(10, 0, 40));
            int decisionsBeforeGap = decisions.size();

            // Act — 10:00:40 이후 체결이 끊겼다가 10:14:00에 1건
            tick(pipeline, SYMBOL, "27300", LocalTime.of(10, 14));

            // Assert — 10:00:40~10:14:00 사이에는 판정할 체결이 없었다(타이머 판정 없음)
            assertThat(decisionsBeforeGap).as("교차 직후 유지 대기 결정 1건뿐").isEqualTo(1);
            assertThat(candidateTimes()).containsExactly(LocalTime.of(10, 14));
            assertThat(decisions.getLast().triggerPrice()).isEqualByComparingTo("27300");
        }

        @Test
        @DisplayName("AC-05 보조 ②·E1 — 체결 시각이 전환 체결보다 앞선 체결은 유지 미충족이며 전환 체결 시각을 바꾸지 않는다")
        void tradeBeforeTransition_isNotSatisfied() {
            // Arrange
            FilterPipeline pipeline = domesticPipeline();
            store.setGrade(SYMBOL, HORIZON, Grade.HOLD, Duration.ofHours(1));
            tick(pipeline, Grade.BUY, TEN);

            // Act — 순서가 뒤바뀐 체결(09:59:50)
            tick(pipeline, Grade.BUY, LocalTime.of(9, 59, 50));

            // Assert
            assertThat(candidates()).isZero();
            assertThat(store.pending(SYMBOL, HORIZON).orElseThrow().since())
                    .isEqualTo(kst(LocalDate.of(2026, 9, 29), TEN).toInstant());
            assertThat(stageCount("dwell", "block")).isEqualTo(2.0);
        }

        @Test
        @DisplayName("AC-06 — 데드존 체류 시간은 유지 시간에 포함되고, 판정은 일치 체결에서만 일어난다")
        void deadZoneTime_countsTowardDwell() {
            // Arrange — 기준 HOLD, 10:00:00 BUY 교차 후 10:00:40까지 BUY 일치 체결
            FilterPipeline pipeline =
                    pipeline(deadZoneReference(), FilterTestFixtures.DOMESTIC_SESSION_CLOCK);
            store.setGrade(SYMBOL, HORIZON, Grade.HOLD, Duration.ofHours(1));
            ticksEvery10s(pipeline, Market.DOMESTIC, "25600", TEN, LocalTime.of(10, 0, 40));
            double dwellEvaluations = stageCount("dwell", "block");

            // Act — 10:00:50~10:10:50 데드존 체결만, 이어서 10:11:00 BUY 일치 체결
            ticksEvery10s(
                    pipeline,
                    Market.DOMESTIC,
                    "25200",
                    LocalTime.of(10, 0, 50),
                    LocalTime.of(10, 10, 50));
            PendingTransition afterDeadZone = store.pending(SYMBOL, HORIZON).orElseThrow();
            double dwellEvaluationsAfterDeadZone = stageCount("dwell", "block");
            tick(pipeline, SYMBOL, "25600", LocalTime.of(10, 11));

            // Assert
            assertThat(afterDeadZone.since())
                    .isEqualTo(kst(LocalDate.of(2026, 9, 29), TEN).toInstant());
            assertThat(dwellEvaluationsAfterDeadZone)
                    .as("데드존 체결은 유지 단계를 평가하지 않는다")
                    .isEqualTo(dwellEvaluations);
            assertThat(candidateTimes())
                    .as("데드존 체결(10:10:00 이후 포함)에서는 발송 후보가 없고 10:11:00 일치 체결에서 1건")
                    .containsExactly(LocalTime.of(10, 11));
        }
    }

    @Nested
    @DisplayName("기준 등급·무효화·교체 (REQ-002/003/004)")
    class Baseline {

        @Test
        @DisplayName("AC-02 ① — BUY→SELL→BUY 교차가 이어지면 측정은 마지막 교차(10:06:00)부터 HOLD→BUY로 다시 잰다")
        void repeatedCrossings_restartFromLastCrossingAgainstBaseline() {
            // Arrange — 기준 HOLD
            FilterPipeline pipeline = domesticPipeline();
            store.setGrade(SYMBOL, HORIZON, Grade.HOLD, Duration.ofHours(1));

            // Act
            ticksEvery10s(pipeline, Grade.BUY, TEN, LocalTime.of(10, 2, 50));
            ticksEvery10s(pipeline, Grade.SELL, LocalTime.of(10, 3), LocalTime.of(10, 5, 50));
            ticksEvery10s(pipeline, Grade.BUY, LocalTime.of(10, 6), LocalTime.of(10, 16, 10));

            // Assert
            assertThat(candidateTimes()).containsExactly(LocalTime.of(10, 16));
            assertThat(decisions.stream().filter(AlertDecision::isCandidate))
                    .singleElement()
                    .extracting(AlertDecision::fromGrade, AlertDecision::toGrade)
                    .containsExactly(Grade.HOLD, Grade.BUY);
        }

        @Test
        @DisplayName("AC-03 — A→B→A(유지 시간 안)는 무효화되고, 되돌아온 A가 오래 유지돼도 역방향 후보는 열리지 않는다")
        void returnWithinDwell_voidsWithoutReverseCandidate() {
            // Arrange
            FilterPipeline pipeline = domesticPipeline();
            store.setGrade(SYMBOL, HORIZON, Grade.HOLD, Duration.ofHours(1));
            ticksEvery10s(pipeline, Grade.BUY, TEN, LocalTime.of(10, 1, 50));
            int decisionsBeforeReturn = decisions.size();

            // Act — 10:02:00 HOLD로 되돌아온 뒤 10:20:00까지 HOLD 영역 체결만
            tick(pipeline, Grade.HOLD, LocalTime.of(10, 2));
            Grade gradeAtReturn = store.gradeOf(SYMBOL, HORIZON);
            ticksEvery10s(pipeline, Grade.HOLD, LocalTime.of(10, 2, 10), LocalTime.of(10, 20));

            // Assert
            assertThat(decisions).as("10:02:00 이후 결정 0건").hasSize(decisionsBeforeReturn);
            assertThat(store.pending(SYMBOL, HORIZON)).isEmpty();
            assertThat(gradeAtReturn).as("유효 등급은 즉시 갱신(REQ-003 후단)").isEqualTo(Grade.HOLD);
            assertThat(stageCount("dwell", "voided")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("AC-04 — A→B→C(유지 시간 안)는 기준 등급 A에서 C로의 후보(HOLD→STRONG_BUY, 15분)다")
        void thirdGrade_replacesCandidateFromBaseline() {
            // Arrange
            FilterPipeline pipeline = domesticPipeline();
            store.setGrade(SYMBOL, HORIZON, Grade.HOLD, Duration.ofHours(1));

            // Act
            ticksEvery10s(pipeline, Grade.BUY, TEN, LocalTime.of(10, 1, 50));
            ticksEvery10s(pipeline, Grade.STRONG_BUY, LocalTime.of(10, 2), LocalTime.of(10, 18));

            // Assert — BUY→STRONG_BUY(10분)라면 10:12:00이었을 것이다
            assertThat(candidateTimes()).containsExactly(LocalTime.of(10, 17));
            assertThat(decisions.stream().filter(AlertDecision::isCandidate))
                    .singleElement()
                    .extracting(
                            AlertDecision::fromGrade,
                            AlertDecision::toGrade,
                            AlertDecision::tier,
                            AlertDecision::transitionType)
                    .containsExactly(Grade.HOLD, Grade.STRONG_BUY, 1, TransitionType.HOLD_ENTRY);
        }

        @Test
        @DisplayName("AC-05 보조 ① — 유지 시간을 이미 채운 BUY 후보도 SELL 일치 체결이 오면 HOLD→SELL로 교체되고 발송 후보는 없다")
        void satisfiedCandidate_isReplacedByThirdGrade() {
            // Arrange
            FilterPipeline pipeline = domesticPipeline();
            store.setGrade(SYMBOL, HORIZON, Grade.HOLD, Duration.ofHours(1));
            ticksEvery10s(pipeline, Grade.BUY, TEN, LocalTime.of(10, 0, 40));

            // Act — 10:14:00 SELL 일치 체결
            tick(pipeline, Grade.SELL, LocalTime.of(10, 14));

            // Assert
            assertThat(candidates()).isZero();
            assertThat(store.pending(SYMBOL, HORIZON).orElseThrow())
                    .extracting(
                            PendingTransition::from,
                            PendingTransition::to,
                            PendingTransition::since)
                    .containsExactly(
                            Grade.HOLD,
                            Grade.SELL,
                            kst(LocalDate.of(2026, 9, 29), LocalTime.of(10, 14)).toInstant());
        }

        @Test
        @DisplayName("AC-12 — 측정 시작점은 연속 구간의 첫 체결(10:00:00)이다 — 데드존에서 돌아온 10:04:10이 아니다")
        void measurementStartsAtFirstTradeOfRun() {
            // Arrange — 데드존 폭이 있는 밴드, 기준 HOLD
            FilterPipeline pipeline =
                    pipeline(deadZoneReference(), FilterTestFixtures.DOMESTIC_SESSION_CLOCK);
            store.setGrade(SYMBOL, HORIZON, Grade.HOLD, Duration.ofHours(1));

            // Act
            ticksEvery10s(pipeline, Market.DOMESTIC, "25600", TEN, LocalTime.of(10, 2));
            ticksEvery10s(
                    pipeline,
                    Market.DOMESTIC,
                    "25200",
                    LocalTime.of(10, 2, 10),
                    LocalTime.of(10, 4));
            ticksEvery10s(
                    pipeline,
                    Market.DOMESTIC,
                    "25600",
                    LocalTime.of(10, 4, 10),
                    LocalTime.of(10, 9, 50));
            PendingTransition beforeDwell = store.pending(SYMBOL, HORIZON).orElseThrow();
            ticksEvery10s(
                    pipeline, Market.DOMESTIC, "25600", LocalTime.of(10, 10), LocalTime.of(10, 15));

            // Assert
            assertThat(beforeDwell.since())
                    .isEqualTo(kst(LocalDate.of(2026, 9, 29), TEN).toInstant());
            assertThat(candidateTimes()).containsExactly(LocalTime.of(10, 10));
        }

        @Test
        @DisplayName("AC-12 ③ 대조 — 기준 등급 복귀(무효화) 뒤 재교차하면 구간이 끊겨 측정 시작점이 10:01:30으로 바뀐다")
        void returnAndRecross_restartsMeasurement() {
            // Arrange
            FilterPipeline pipeline =
                    pipeline(deadZoneReference(), FilterTestFixtures.DOMESTIC_SESSION_CLOCK);
            store.setGrade(SYMBOL, HORIZON, Grade.HOLD, Duration.ofHours(1));

            // Act — 10:00:00 BUY, 10:01:00 HOLD(기준 등급 복귀), 10:01:30 BUY 재교차
            ticksEvery10s(pipeline, Market.DOMESTIC, "25600", TEN, LocalTime.of(10, 0, 50));
            tick(pipeline, SYMBOL, "24000", LocalTime.of(10, 1));
            ticksEvery10s(
                    pipeline,
                    Market.DOMESTIC,
                    "25600",
                    LocalTime.of(10, 1, 30),
                    LocalTime.of(10, 12));

            // Assert
            assertThat(candidateTimes()).containsExactly(LocalTime.of(10, 11, 30));
        }
    }

    @Nested
    @DisplayName("AC-14 — 유지 시간 억제 사유와 유지 단계 계측 (REQ-013/015)")
    class DwellMetrics {

        private double pendingGauge() {
            return registry.get(FilterMetrics.CONFIRM_PENDING).gauge().value();
        }

        @Test
        @DisplayName("통과 1·차단 3·무효화 3(기준 등급 복귀·제3 등급 교체·묵은 후보 정리), 대기 게이지는 유지 대기를 포함하고 무효화·종결 뒤 뺀다")
        void dwellStageCountsAndGauge() {
            // Arrange — 종목 X(기준 HOLD)와 묵은 후보를 심은 종목 Y
            StockReference x = ladder(Market.DOMESTIC);
            StockReference y =
                    new StockReference(
                            2L,
                            "000660",
                            Market.DOMESTIC,
                            FilterTestFixtures.PREV_DAY,
                            bd("25000"),
                            bd("1000000"),
                            bd("1000000"),
                            x.bands());
            FilterPipeline pipeline =
                    FilterPipelines.create(
                            holderOf(x, y),
                            store,
                            decisions::add,
                            registry,
                            FilterTestFixtures.DOMESTIC_SESSION_CLOCK);
            store.setGrade(SYMBOL, HORIZON, Grade.HOLD, Duration.ofHours(1));
            store.setGrade("000660", HORIZON, Grade.BUY, Duration.ofHours(1));
            store.savePending(
                    "000660",
                    HORIZON,
                    PendingTransition.started(
                            Grade.HOLD,
                            Grade.SELL,
                            kst(LocalDate.of(2026, 9, 29), LocalTime.of(10, 5)).toInstant()),
                    Duration.ofHours(1));
            List<Double> gauge = new ArrayList<>();

            // Act
            tick(pipeline, Grade.BUY, TEN); // ① 교차 → 차단 1
            gauge.add(pendingGauge());
            tick(pipeline, Grade.HOLD, LocalTime.of(10, 0, 10)); // ② 기준 등급 복귀 → 무효화 1
            gauge.add(pendingGauge());
            tick(pipeline, Grade.BUY, LocalTime.of(10, 1)); // ③ 새 교차 → 차단 2
            gauge.add(pendingGauge());
            tick(pipeline, Grade.STRONG_BUY, LocalTime.of(10, 1, 10)); // ④ 제3 등급 교체 → 무효화 2, 차단 3
            gauge.add(pendingGauge());
            tick(pipeline, Grade.STRONG_BUY, LocalTime.of(10, 16, 10)); // ⑥ 유지 15분 충족 → 통과 1
            gauge.add(pendingGauge());
            tick(
                    pipeline,
                    "000660",
                    PRICE.get(Grade.BUY),
                    LocalTime.of(10, 5, 30)); // Y 묵은 후보 → 무효화 3

            // Assert
            assertThat(
                            List.of(
                                    stageCount("dwell", "pass"),
                                    stageCount("dwell", "block"),
                                    stageCount("dwell", "voided")))
                    .containsExactly(1.0, 3.0, 3.0);
            assertThat(gauge).as("X의 게이지 추이 ①~④·⑥").containsExactly(1.0, 0.0, 1.0, 1.0, 0.0);
            assertThat(
                            decisions.stream()
                                    .filter(decision -> !decision.isCandidate())
                                    .map(AlertDecision::suppressionReason)
                                    .distinct())
                    .as("대기 억제 사유는 쿨다운과 다른 유지 시간 사유")
                    .containsExactly(SuppressionReason.DWELL_PENDING);
            assertThat(store.pending("000660", HORIZON)).isEmpty();
        }
    }

    @Nested
    @DisplayName("AC-15 — 유지 확증과 쿨다운의 상호작용 (REQ-003/007)")
    class DwellAndCooldown {

        @Test
        @DisplayName("유지 확증을 통과한 BUY→HOLD는 Tier 2 쿨다운으로 종결되고, 그 뒤 기준 등급은 HOLD다")
        void dwellPassThenCooldown_movesBaseline() {
            // Arrange — 10:10:00 HOLD→BUY 발송 후보(HOLD 진입 쿨다운 30분)
            FilterPipeline pipeline = domesticPipeline();
            store.setGrade(SYMBOL, HORIZON, Grade.HOLD, Duration.ofHours(1));
            ticksEvery10s(pipeline, Grade.BUY, TEN, LocalTime.of(10, 11, 50));

            // Act — 10:12:00 BUY→HOLD, 10:20:00까지 HOLD, 이어서 10:21:00 BUY 교차
            ticksEvery10s(pipeline, Grade.HOLD, LocalTime.of(10, 12), LocalTime.of(10, 20));
            tick(pipeline, Grade.BUY, LocalTime.of(10, 21));

            // Assert
            assertThat(candidateTimes()).containsExactly(LocalTime.of(10, 10));
            assertThat(
                            decisions.stream()
                                    .filter(
                                            decision ->
                                                    decision.suppressionReason()
                                                            == SuppressionReason.COOLDOWN_ACTIVE))
                    .singleElement()
                    .extracting(AlertDecision::fromGrade, AlertDecision::toGrade)
                    .containsExactly(Grade.BUY, Grade.HOLD);
            assertThat(store.pending(SYMBOL, HORIZON).orElseThrow().from())
                    .as("쿨다운 종결도 기준 등급을 옮긴다")
                    .isEqualTo(Grade.HOLD);
        }

        @Test
        @DisplayName("보조 — BUY→HOLD가 7분 안에 BUY로 되돌아오면 쿨다운 검사까지 가지 않고 무효화된다")
        void returnBeforeDwell_neverReachesCooldown() {
            // Arrange
            FilterPipeline pipeline = domesticPipeline();
            store.setGrade(SYMBOL, HORIZON, Grade.HOLD, Duration.ofHours(1));
            ticksEvery10s(pipeline, Grade.BUY, TEN, LocalTime.of(10, 11, 50));

            // Act
            ticksEvery10s(pipeline, Grade.HOLD, LocalTime.of(10, 12), LocalTime.of(10, 14, 50));
            tick(pipeline, Grade.BUY, LocalTime.of(10, 15));

            // Assert
            assertThat(suppressedBy(SuppressionReason.COOLDOWN_ACTIVE)).isZero();
            assertThat(stageCount("dwell", "voided")).isEqualTo(1.0);
            assertThat(store.pending(SYMBOL, HORIZON)).isEmpty();
        }
    }

    @Nested
    @DisplayName("AC-07 — 20칸 전부 유지 시간 유형 대응, 경계 판정 (REQ-008)")
    class TwentyCells {

        @ParameterizedTest(name = "{0} → {1} = {2}분")
        @CsvSource({
            "STRONG_BUY,  BUY,         7",
            "STRONG_BUY,  HOLD,        7",
            "STRONG_BUY,  SELL,        5",
            "STRONG_BUY,  STRONG_SELL, 5",
            "BUY,         STRONG_BUY,  10",
            "BUY,         HOLD,        7",
            "BUY,         SELL,        5",
            "BUY,         STRONG_SELL, 5",
            "HOLD,        STRONG_BUY,  15",
            "HOLD,        BUY,         10",
            "HOLD,        SELL,        10",
            "HOLD,        STRONG_SELL, 15",
            "SELL,        STRONG_BUY,  5",
            "SELL,        BUY,         5",
            "SELL,        HOLD,        7",
            "SELL,        STRONG_SELL, 10",
            "STRONG_SELL, STRONG_BUY,  5",
            "STRONG_SELL, BUY,         5",
            "STRONG_SELL, HOLD,        7",
            "STRONG_SELL, SELL,        7",
        })
        @DisplayName("교차 직후에는 발송 후보가 없고 10:00:00 + T 체결에서 정확히 1건, T − 10초 체결에서는 없다")
        void cell_emitsCandidateExactlyAtDwell(Grade from, Grade to, int minutes) {
            // Arrange
            FilterPipeline pipeline = domesticPipeline();
            store.setGrade(SYMBOL, HORIZON, from, Duration.ofHours(1));
            LocalTime dwellEnd = TEN.plusMinutes(minutes);

            // Act
            ticksEvery10s(pipeline, to, TEN, dwellEnd.minusSeconds(10));
            long beforeDwellEnd = candidates();
            tick(pipeline, to, dwellEnd);

            // Assert
            assertThat(beforeDwellEnd).as("T − 10초까지 발송 후보 없음(즉시 발송 칸 없음)").isZero();
            assertThat(candidateTimes()).containsExactly(dwellEnd);
        }
    }

    @Nested
    @DisplayName("AC-08 — 가드(개장 직후 제외)는 유지 측정을 리셋하지 않는다 (REQ-009)")
    class OpeningGuard {

        @ParameterizedTest(name = "{0} → {1}, 교차 {2} → 발송 후보 {3}")
        @CsvSource({
            "BUY,  SELL,       09:03:00, 09:15:00",
            "HOLD, STRONG_BUY, 09:03:00, 09:18:00",
            "HOLD, STRONG_BUY, 09:00:00, 09:15:00",
        })
        @DisplayName("개장 직후 제외 동안에도 유지 시간이 흐르고, 가드가 풀린 뒤 누적 유지 시간으로 판정한다")
        void openingGuard_doesNotResetDwell(
                Grade from, Grade to, LocalTime crossing, LocalTime expected) {
            // Arrange
            FilterPipeline pipeline = domesticPipeline();
            store.setGrade(SYMBOL, HORIZON, from, Duration.ofHours(1));

            // Act
            ticksEvery10s(pipeline, to, crossing, expected.plusMinutes(1));

            // Assert
            assertThat(candidateTimes()).containsExactly(expected);
        }
    }

    @Nested
    @DisplayName("AC-09 — 마감 경계와 마감 전 제외 구간 (REQ-010)")
    class ClosingBoundary {

        static Stream<Arguments> lastCrossings() {
            return Stream.of(
                    Arguments.of(Grade.BUY, Grade.SELL, "15:15:00", true),
                    Arguments.of(Grade.BUY, Grade.SELL, "15:15:10", false),
                    Arguments.of(Grade.BUY, Grade.HOLD, "15:13:00", true),
                    Arguments.of(Grade.BUY, Grade.HOLD, "15:13:10", false),
                    Arguments.of(Grade.HOLD, Grade.BUY, "15:10:00", true),
                    Arguments.of(Grade.HOLD, Grade.BUY, "15:10:10", false),
                    Arguments.of(Grade.HOLD, Grade.STRONG_BUY, "15:05:00", true),
                    Arguments.of(Grade.HOLD, Grade.STRONG_BUY, "15:05:10", false));
        }

        @ParameterizedTest(name = "{0} → {1} 교차 {2} — 발송 후보 {3}")
        @MethodSource("lastCrossings")
        @DisplayName("(a) 15:20:00 − T 교차는 15:20:00 체결에서 1건, 10초 늦은 교차는 그날 0건이다")
        void lastCrossing_beforeCloseExclusion(
                Grade baseline, Grade to, LocalTime crossing, boolean emits) {
            // Arrange — HOLD 기준은 전일 종가(HOLD 영역) 초기값을 그대로, BUY 기준은 저장소에 직접 기록(열린 후보·쿨다운 없음)
            FilterPipeline pipeline = pipeline(ladder(Market.DOMESTIC), DOMESTIC_CLOSING_CLOCK);
            if (baseline != Grade.HOLD) {
                store.setGrade(SYMBOL, HORIZON, baseline, Duration.ofMinutes(30));
            }
            tick(pipeline, baseline, LocalTime.of(15, 0));

            // Act — 교차 후 15:29:50까지 교차 등급 영역 체결
            ticksEvery10s(pipeline, to, crossing, LocalTime.of(15, 29, 50));

            // Assert
            assertThat(candidateTimes())
                    .isEqualTo(emits ? List.of(LocalTime.of(15, 20)) : List.of());
        }

        @Test
        @DisplayName("(b) 15:17:00 HOLD→BUY(10분) 교차는 15:29:50까지 이어져도 그날 발송 후보 0건이다")
        void lateCrossing_neverEmits() {
            // Arrange
            FilterPipeline pipeline = pipeline(ladder(Market.DOMESTIC), DOMESTIC_CLOSING_CLOCK);
            tick(pipeline, Grade.HOLD, LocalTime.of(15, 0));

            // Act
            ticksEvery10s(pipeline, Grade.BUY, LocalTime.of(15, 17), LocalTime.of(15, 29, 50));

            // Assert
            assertThat(candidates()).isZero();
            assertThat(store.pending(SYMBOL, HORIZON)).isPresent();
        }

        @Test
        @DisplayName("(c) 마감 소멸 — 후보 TTL은 마감까지 남은 시간이고, 만료 뒤 다음 거래일은 새 교차로 새로 잰다")
        void closeExpiry_startsFreshNextTradingDay() {
            // Arrange — 15:17:00 HOLD→BUY 후보(마감까지 유지 시간 미충족)
            FilterPipeline today = pipeline(ladder(Market.DOMESTIC), DOMESTIC_CLOSING_CLOCK);
            tick(today, Grade.HOLD, LocalTime.of(15, 0));
            tick(today, Grade.BUY, LocalTime.of(15, 17));
            Duration pendingTtl = store.pendingTtlOf(SYMBOL, HORIZON);

            // Act — 마감 만료 모사 후 다음 거래일 10:00:00 고정 시계의 새 파이프라인(같은 저장소)
            store.expireIntraday(SYMBOL, HORIZON);
            FilterPipeline nextDay =
                    pipeline(ladder(Market.DOMESTIC), kstClock(2026, 9, 30, 10, 0));
            tick(nextDay, Grade.BUY, TEN);

            // Assert
            assertThat(pendingTtl).as("고정 시계 15:00 기준 마감까지 30분").isEqualTo(Duration.ofMinutes(30));
            assertThat(candidates()).isZero();
            assertThat(store.pending(SYMBOL, HORIZON).orElseThrow())
                    .extracting(PendingTransition::from, PendingTransition::since)
                    .containsExactly(Grade.HOLD, kst(LocalDate.of(2026, 9, 30), TEN).toInstant());
        }
    }

    @Nested
    @DisplayName("해외 시장 경계 (E2·E6)")
    class Overseas {

        @Test
        @DisplayName("E2 — KST 자정을 넘긴 교차·유지도 체결 시각 조립 규칙대로 이어서 잰다")
        void midnightCrossing_isContinuous() {
            // Arrange — 2026-09-30 00:10 KST(= 09-29 11:10 EDT) 시계, BUY→SELL(직접 5분)
            FilterPipeline pipeline =
                    pipeline(ladder(Market.OVERSEAS), kstClock(2026, 9, 30, 0, 10));
            store.setGrade(SYMBOL, HORIZON, Grade.BUY, Duration.ofHours(1));

            // Act — 23:58:00 KST 교차 → 자정 넘어 00:04:00까지
            ticksEvery10s(
                    pipeline,
                    Market.OVERSEAS,
                    PRICE.get(Grade.SELL),
                    LocalTime.of(23, 58),
                    LocalTime.of(23, 59, 50));
            ticksEvery10s(
                    pipeline,
                    Market.OVERSEAS,
                    PRICE.get(Grade.SELL),
                    LocalTime.MIDNIGHT,
                    LocalTime.of(0, 4));

            // Assert
            assertThat(candidateTimes()).containsExactly(LocalTime.of(0, 3));
        }

        @ParameterizedTest(name = "시계 {0} KST, 교차 {1} KST → 발송 후보 {2}")
        @CsvSource({
            // 개장 쪽: 09:30 ET(22:30 KST) HOLD→STRONG_BUY(15분) → 09:45:00 ET 가드 해제와 유지 충족이 동시
            "2026-09-29T22:50, 22:30:00, 22:45:00",
            // 마감 쪽: 15:35:00 ET(04:35 KST) 교차가 마지막 — 15:50:00 ET(04:50 KST) 체결에서 1건
            "2026-09-30T04:55, 04:35:00, 04:50:00",
            // 마감 쪽: 10초 늦은 교차는 그날 0건
            "2026-09-30T04:55, 04:35:10, ",
        })
        @DisplayName("E6 — 해외 가드 창(09:45:00~15:50:00 ET) 경계가 design.md §6 해외 열과 같다")
        void overseasWindow_matchesDesignBoundaries(
                String clockAt, LocalTime crossing, LocalTime expected) {
            // Arrange
            ZonedDateTime now = LocalDateTime.parse(clockAt).atZone(MarketSession.KST);
            FilterPipeline pipeline =
                    pipeline(
                            ladder(Market.OVERSEAS),
                            Clock.fixed(now.toInstant(), MarketSession.KST));
            store.setGrade(SYMBOL, HORIZON, Grade.HOLD, Duration.ofHours(1));

            // Act
            ticksEvery10s(
                    pipeline,
                    Market.OVERSEAS,
                    PRICE.get(Grade.STRONG_BUY),
                    crossing,
                    now.toLocalTime().minusSeconds(10));

            // Assert
            assertThat(candidateTimes())
                    .isEqualTo(expected == null ? List.of() : List.of(expected));
        }
    }

    @Nested
    @DisplayName("AC-02 ② — 2026-09-30 관측 방향의 회귀 픽스처 (데드존 폭 0, 1틱 플립플롭)")
    class FlipFlopRegression {

        /** 68분간 10초 간격 교차 = 408회. */
        private static final int CROSSINGS = 408;

        /** 교차당 같은 체결 시각 체결 수 — FILTER-001 규칙에서 HOLD 진입 확증 5회를 채우는 밀도(판별력, N2). */
        private static final int TRADES_PER_CROSSING = 5;

        static Stream<Arguments> fixtures() {
            return Stream.of(
                    Arguments.of(
                            "267260",
                            "666000",
                            zeroWidthBands(
                                    "600000", "666900", "733000", Grade.STRONG_BUY, Grade.HOLD),
                            "666000",
                            "667000"),
                    Arguments.of(
                            "004020",
                            "30150",
                            zeroWidthBands("27000", "30150", "33000", Grade.BUY, Grade.HOLD),
                            "30100",
                            "30150"));
        }

        @ParameterizedTest(name = "{0} — {3}원 ↔ {4}원")
        @MethodSource("fixtures")
        @DisplayName("기준 HOLD에서 1틱마다 교차해도 발송 후보·쿨다운 억제 결정은 0건이다")
        void flipFlop_producesNoCandidateNorCooldown(
                String symbol,
                String prevClose,
                HorizonBands bands,
                String crossingPrice,
                String baselinePrice) {
            // Arrange — 기준 등급 HOLD
            FilterPipeline pipeline =
                    pipeline(
                            reference(symbol, Market.DOMESTIC, prevClose, bands),
                            FilterTestFixtures.DOMESTIC_SESSION_CLOCK);
            store.setGrade(symbol, HORIZON, Grade.HOLD, Duration.ofHours(1));

            // Act — 10:00:00부터 10초마다 교차 등급 ↔ HOLD, 교차마다 같은 체결 시각 체결 5건
            for (int i = 0; i < CROSSINGS; i++) {
                LocalTime time = TEN.plusSeconds(10L * i);
                String price = i % 2 == 0 ? crossingPrice : baselinePrice;
                for (int trade = 0; trade < TRADES_PER_CROSSING; trade++) {
                    tick(pipeline, symbol, price, time);
                }
            }

            // Assert — 절대 단언(대조 테스트 없음, Q2)
            assertThat(candidates()).as("발송 후보 결정 수").isZero();
            assertThat(suppressedBy(SuppressionReason.COOLDOWN_ACTIVE)).as("쿨다운 억제 결정 수").isZero();
            assertThat(stageCount("band", "transition"))
                    .as("유효 등급은 교차마다 갱신된다 — 원인(데드존 폭 0)은 남아 있다(spec.md §1.3)")
                    .isEqualTo(CROSSINGS);
            assertThat(stageCount("dwell", "voided"))
                    .as("기준 등급(HOLD)으로 되돌아온 교차 수만큼 무효화")
                    .isEqualTo(CROSSINGS / 2.0);
        }
    }
}
