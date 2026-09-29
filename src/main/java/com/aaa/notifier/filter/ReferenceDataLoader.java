package com.aaa.notifier.filter;

import com.aaa.notifier.filter.ReferenceRows.BandRow;
import com.aaa.notifier.filter.ReferenceRows.OhlcvRow;
import com.aaa.notifier.filter.ReferenceRows.StockRow;
import com.aaa.notifier.stream.Market;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * 장전 참조 데이터·밴드 적재기 (REQ-NOTIFIER-FILTER-001, plan.md §D.1, design.md §5).
 *
 * <p>{@code stocks}·{@code daily_ohlcv}·{@code signal_price_bands}를 <b>각 1회</b> SELECT한다 — 종목 목록을
 * {@code IN (서브쿼리)}로 묶어 종목당 쿼리(N+1)를 만들지 않는다. 이 클래스는 장전 cron·기동 경로에서만 호출되며, 틱 경로는 결과 스냅샷만
 * 읽는다(REQ-002). 행 → 참조 데이터 계산은 {@link ReferenceAssembler}가 맡는다.
 */
@RequiredArgsConstructor
public class ReferenceDataLoader {

    /** 감시 대상 종목 조건 — 활성·관심목록 유지·상장 유지. */
    private static final String WATCHED_CONDITION =
            "active = TRUE AND watchlist_removed_at IS NULL AND delisted_at IS NULL";

    static final String STOCKS_SQL =
            "SELECT id, symbol, market FROM stocks WHERE " + WATCHED_CONDITION;

    static final String OHLCV_SQL =
            "SELECT stock_id, trade_date, high_price, low_price, close_price, volume"
                    + " FROM daily_ohlcv WHERE trade_date >= ? AND trade_date < ?"
                    + " AND stock_id IN (SELECT id FROM stocks WHERE "
                    + WATCHED_CONDITION
                    + ") ORDER BY stock_id, trade_date";

    static final String BANDS_SQL =
            "SELECT stock_id, trade_date, horizon, boundary_set, price_low, price_high, signal_class"
                    + " FROM signal_price_bands WHERE trade_date >= ? AND trade_date < ?"
                    + " AND stock_id IN (SELECT id FROM stocks WHERE "
                    + WATCHED_CONDITION
                    + ") ORDER BY stock_id, horizon, boundary_set, band_seq";

    private static final Map<String, Market> MARKET_BY_CODE =
            Map.of(
                    "KOSPI", Market.DOMESTIC,
                    "KOSDAQ", Market.DOMESTIC,
                    "KRX", Market.DOMESTIC,
                    "NYSE", Market.OVERSEAS,
                    "NASDAQ", Market.OVERSEAS,
                    "AMEX", Market.OVERSEAS,
                    "US", Market.OVERSEAS);

    private static final RowMapper<StockRow> STOCK_MAPPER =
            (rs, rowNum) ->
                    new StockRow(
                            rs.getLong("id"),
                            rs.getString("symbol"),
                            toMarket(rs.getString("market")).orElse(null));

    private static final RowMapper<OhlcvRow> OHLCV_MAPPER =
            (rs, rowNum) ->
                    new OhlcvRow(
                            rs.getLong("stock_id"),
                            rs.getObject("trade_date", LocalDate.class),
                            rs.getBigDecimal("high_price"),
                            rs.getBigDecimal("low_price"),
                            rs.getBigDecimal("close_price"),
                            rs.getLong("volume"));

    private static final RowMapper<BandRow> BAND_MAPPER =
            (rs, rowNum) ->
                    new BandRow(
                            rs.getLong("stock_id"),
                            rs.getObject("trade_date", LocalDate.class),
                            rs.getString("horizon"),
                            rs.getString("boundary_set"),
                            rs.getBigDecimal("price_low"),
                            rs.getBigDecimal("price_high"),
                            rs.getString("signal_class"));

    private final JdbcTemplate jdbcTemplate;

    /** {@code daily_ohlcv}·밴드 조회 창(달력일). */
    private final int lookbackDays;

    /**
     * 참조 데이터와 밴드를 적재한다.
     *
     * @param marketToday 시장별 현지 '당일'
     * @return 불변 스냅샷
     * @throws org.springframework.dao.DataAccessException DB 접근 실패 — 호출자가 이전 스냅샷 유지로 폴백한다
     */
    public ReferenceSnapshot load(Map<Market, LocalDate> marketToday) {
        LocalDate upper =
                marketToday.values().stream().max(Comparator.naturalOrder()).orElseThrow();
        LocalDate lower =
                marketToday.values().stream()
                        .min(Comparator.naturalOrder())
                        .orElseThrow()
                        .minusDays(lookbackDays);

        List<StockRow> stocks = jdbcTemplate.query(STOCKS_SQL, STOCK_MAPPER);
        List<OhlcvRow> ohlcv = jdbcTemplate.query(OHLCV_SQL, OHLCV_MAPPER, lower, upper);
        List<BandRow> bands = jdbcTemplate.query(BANDS_SQL, BAND_MAPPER, lower, upper);

        Map<Long, List<OhlcvRow>> ohlcvByStock =
                ohlcv.stream().collect(Collectors.groupingBy(OhlcvRow::stockId));
        Map<Long, List<BandRow>> bandsByStock =
                bands.stream().collect(Collectors.groupingBy(BandRow::stockId));

        return new ReferenceSnapshot(
                stocks.stream()
                        .filter(stock -> Objects.nonNull(stock.market()))
                        .flatMap(
                                stock ->
                                        ReferenceAssembler.assemble(
                                                stock,
                                                marketToday.get(stock.market()),
                                                ohlcvByStock.getOrDefault(stock.id(), List.of()),
                                                bandsByStock.getOrDefault(stock.id(), List.of()))
                                                .stream())
                        .toList());
    }

    /**
     * {@code stocks.market} 문자열을 시장 구분으로 매핑한다.
     *
     * @param code {@code stocks.market} 원문
     * @return 미지원 값이면 빈 값 — 해당 종목은 적재에서 제외된다
     */
    static Optional<Market> toMarket(String code) {
        return Optional.ofNullable(code == null ? null : MARKET_BY_CODE.get(code));
    }
}
