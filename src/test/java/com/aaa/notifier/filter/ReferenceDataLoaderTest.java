package com.aaa.notifier.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.notifier.filter.ReferenceRows.BandRow;
import com.aaa.notifier.filter.ReferenceRows.OhlcvRow;
import com.aaa.notifier.filter.ReferenceRows.StockRow;
import com.aaa.notifier.stream.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@DisplayName("ReferenceDataLoader — 장전 참조 데이터·밴드 적재 (REQ-001, AC-1 ①)")
class ReferenceDataLoaderTest {

    private static final LocalDate DOMESTIC_TODAY = LocalDate.of(2026, 9, 29);
    private static final LocalDate OVERSEAS_TODAY = LocalDate.of(2026, 9, 28);
    private static final LocalDate PREV_DAY = LocalDate.of(2026, 9, 28);
    private static final Map<Market, LocalDate> TODAY =
            Map.of(Market.DOMESTIC, DOMESTIC_TODAY, Market.OVERSEAS, OVERSEAS_TODAY);

    private StubJdbcTemplate jdbcTemplate;
    private ReferenceDataLoader loader;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new StubJdbcTemplate();
        loader = new ReferenceDataLoader(jdbcTemplate, 45);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /** 종가 30,000·고저 ±100·거래량 1,000인 일봉 16개(전일 = PREV_DAY) + 당일 행 1개(제외 대상). */
    private static List<OhlcvRow> flatOhlcv(long stockId) {
        List<OhlcvRow> rows = new ArrayList<>();
        for (int i = 15; i >= 0; i--) {
            rows.add(
                    new OhlcvRow(
                            stockId,
                            PREV_DAY.minusDays(i),
                            bd("30100"),
                            bd("29900"),
                            bd("30000"),
                            1_000L));
        }
        rows.add(new OhlcvRow(stockId, DOMESTIC_TODAY, bd("99999"), bd("1"), bd("50000"), 9L));
        return rows;
    }

    private static List<BandRow> bands(long stockId, LocalDate tradeDate) {
        return List.of(
                new BandRow(stockId, tradeDate, "D20", "PROMOTE", bd("29000"), bd("30490"), "BUY"),
                new BandRow(
                        stockId,
                        tradeDate,
                        "D20",
                        "PROMOTE",
                        bd("30500"),
                        bd("31000"),
                        "STRONG_BUY"),
                new BandRow(stockId, tradeDate, "D20", "DEMOTE", bd("29000"), bd("29790"), "BUY"),
                new BandRow(
                        stockId,
                        tradeDate,
                        "D20",
                        "DEMOTE",
                        bd("29800"),
                        bd("31000"),
                        "STRONG_BUY"),
                // DEMOTE 파티션이 없는 D60 — 판정 불가라 적재에서 제외되어야 한다
                new BandRow(
                        stockId, tradeDate, "D60", "PROMOTE", bd("29000"), bd("31000"), "HOLD"));
    }

    private void stubQueries(List<StockRow> stocks, List<OhlcvRow> ohlcv, List<BandRow> bands) {
        jdbcTemplate.results.put(ReferenceDataLoader.STOCKS_SQL, stocks);
        jdbcTemplate.results.put(ReferenceDataLoader.OHLCV_SQL, ohlcv);
        jdbcTemplate.results.put(ReferenceDataLoader.BANDS_SQL, bands);
    }

    @Test
    @DisplayName("AC-1 ① — 세 테이블을 각 1회씩만 SELECT한다 (N+1 아님)")
    void load_queriesEachTableExactlyOnce() {
        // Arrange
        List<BandRow> bandRows = new ArrayList<>(bands(1L, PREV_DAY));
        bandRows.addAll(bands(2L, PREV_DAY));
        List<OhlcvRow> ohlcv = new ArrayList<>(flatOhlcv(1L));
        ohlcv.addAll(flatOhlcv(2L));
        stubQueries(
                List.of(
                        new StockRow(1L, "005930", Market.DOMESTIC),
                        new StockRow(2L, "000660", Market.DOMESTIC)),
                ohlcv,
                bandRows);

        // Act
        ReferenceSnapshot snapshot = loader.load(TODAY);

        // Assert
        assertThat(snapshot.stocks()).hasSize(2);
        assertThat(jdbcTemplate.executed)
                .containsExactly(
                        ReferenceDataLoader.STOCKS_SQL,
                        ReferenceDataLoader.OHLCV_SQL,
                        ReferenceDataLoader.BANDS_SQL);
    }

    @Test
    @DisplayName("전일 종가·20일 평균 거래량·ATR(14)을 당일 행을 제외하고 산출한다")
    void load_computesReferenceFromHistoryExcludingToday() {
        // Arrange
        stubQueries(
                List.of(new StockRow(1L, "005930", Market.DOMESTIC)),
                flatOhlcv(1L),
                bands(1L, PREV_DAY));

        // Act
        StockReference reference = loader.load(TODAY).find(Market.DOMESTIC, "005930").orElseThrow();

        // Assert
        assertThat(reference.prevClose()).isEqualByComparingTo("30000");
        assertThat(reference.prevTradeDate()).isEqualTo(PREV_DAY);
        assertThat(reference.averageVolume()).isEqualByComparingTo("1000");
        assertThat(reference.atr()).isEqualByComparingTo("200");
    }

    @Test
    @DisplayName("PROMOTE·DEMOTE 두 파티션이 모두 있는 horizon만 적재한다")
    void load_keepsOnlyHorizonsWithBothPartitions() {
        // Arrange
        stubQueries(
                List.of(new StockRow(1L, "005930", Market.DOMESTIC)),
                flatOhlcv(1L),
                bands(1L, PREV_DAY));

        // Act
        StockReference reference = loader.load(TODAY).find(Market.DOMESTIC, "005930").orElseThrow();

        // Assert
        assertThat(reference.bands()).containsOnlyKeys("D20");
        assertThat(reference.bands().get("D20").demote().lookup(bd("29850")).grade())
                .isEqualTo(Grade.STRONG_BUY);
        assertThat(reference.bands().get("D20").promote().lookup(bd("29850")).grade())
                .isEqualTo(Grade.BUY);
    }

    @Test
    @DisplayName("밴드 기준일이 전일 일봉과 다르면(스윕 누락일의 묵은 밴드) 적재하지 않는다")
    void load_ignoresStaleBands() {
        // Arrange
        stubQueries(
                List.of(new StockRow(1L, "005930", Market.DOMESTIC)),
                flatOhlcv(1L),
                bands(1L, PREV_DAY.minusDays(1)));

        // Act
        ReferenceSnapshot snapshot = loader.load(TODAY);

        // Assert
        assertThat(snapshot.find(Market.DOMESTIC, "005930").orElseThrow().bands()).isEmpty();
    }

    @Test
    @DisplayName("ATR(14)은 일봉 15개 미만이면 산출하지 않는다(null)")
    void load_leavesAtrNullWhenHistoryTooShort() {
        // Arrange
        List<OhlcvRow> shortHistory = flatOhlcv(1L).subList(10, 16);
        stubQueries(
                List.of(new StockRow(1L, "005930", Market.DOMESTIC)),
                shortHistory,
                bands(1L, PREV_DAY));

        // Act
        StockReference reference = loader.load(TODAY).find(Market.DOMESTIC, "005930").orElseThrow();

        // Assert
        assertThat(reference.atr()).isNull();
        assertThat(reference.averageVolume()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("일봉이 전혀 없는 종목은 스냅샷에서 제외한다")
    void load_skipsStocksWithoutHistory() {
        // Arrange
        stubQueries(List.of(new StockRow(9L, "AAPL", Market.OVERSEAS)), List.of(), List.of());

        // Act
        ReferenceSnapshot snapshot = loader.load(TODAY);

        // Assert
        assertThat(snapshot.stocks()).isEmpty();
    }

    @Test
    @DisplayName("stocks.market 문자열을 시장 구분으로 매핑한다 — 미지원 값은 제외")
    void marketMapping_coversKnownValues() {
        assertThat(ReferenceDataLoader.toMarket("KOSPI")).contains(Market.DOMESTIC);
        assertThat(ReferenceDataLoader.toMarket("KOSDAQ")).contains(Market.DOMESTIC);
        assertThat(ReferenceDataLoader.toMarket("NASDAQ")).contains(Market.OVERSEAS);
        assertThat(ReferenceDataLoader.toMarket("AMEX")).contains(Market.OVERSEAS);
        assertThat(ReferenceDataLoader.toMarket("UNKNOWN")).isEmpty();
    }

    /**
     * 실행된 SQL을 기록하고 미리 넣어 둔 행을 돌려주는 가짜 템플릿.
     *
     * <p>Mockito 스텁 대신 서브클래스를 쓰는 이유: 스텁 호출({@code query(eq(...), ...)})은 SQL 인자가 상수가 아니라서
     * SpotBugs(FindSecBugs)가 테스트 코드를 SQL 인젝션 후보로 오탐한다. 오버라이드는 호출이 아니므로 그 패턴에 걸리지 않는다.
     */
    private static final class StubJdbcTemplate extends JdbcTemplate {

        private final Map<String, List<?>> results = new ConcurrentHashMap<>();
        private final List<String> executed = new ArrayList<>();

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
            return record(sql);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            return record(sql);
        }

        @SuppressWarnings("unchecked")
        private <T> List<T> record(String sql) {
            executed.add(sql);
            return (List<T>) results.getOrDefault(sql, List.of());
        }
    }
}
