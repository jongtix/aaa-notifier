package com.aaa.notifier.filter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** 필터 통합 테스트 공용 MySQL 픽스처 — 운영 스키마 대응은 {@code filter/schema.sql} 참조. */
final class FilterDbFixtures {

    /** 픽스처의 기준 '전일'(밴드 기준일 = 직전 일봉 날짜). 통합 테스트 시계는 그다음 날(2026-09-29)이다. */
    static final LocalDate PREV_DAY = LocalDate.of(2026, 9, 28);

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 20, 0);

    private FilterDbFixtures() {}

    /** 모든 픽스처 행을 지운다(FK 역순). */
    static void truncate(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("DELETE FROM notification_log");
        jdbcTemplate.update("DELETE FROM signal_price_bands");
        jdbcTemplate.update("DELETE FROM daily_ohlcv");
        jdbcTemplate.update("DELETE FROM stocks");
    }

    /** 감시 대상 종목 1건을 넣고 {@code stocks.id}를 돌려준다. */
    static long insertStock(JdbcTemplate jdbcTemplate, String symbol, String market) {
        jdbcTemplate.update(
                "INSERT INTO stocks (symbol, market, asset_type, active, created_at, updated_at)"
                        + " VALUES (?, ?, 'STOCK', TRUE, ?, ?)",
                symbol,
                market,
                NOW,
                NOW);
        Long id =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM stocks WHERE symbol = ? AND market = ?",
                        Long.class,
                        symbol,
                        market);
        return id == null ? -1L : id;
    }

    /**
     * 종가 {@code close}·고저 ±{@code halfRange}·거래량 {@code volume}인 일봉을 PREV_DAY까지 {@code days}일 넣는다.
     */
    static void insertFlatOhlcv(
            JdbcTemplate jdbcTemplate,
            long stockId,
            int days,
            String close,
            String halfRange,
            long volume) {
        BigDecimal closePrice = new BigDecimal(close);
        BigDecimal half = new BigDecimal(halfRange);
        for (int i = days - 1; i >= 0; i--) {
            jdbcTemplate.update(
                    "INSERT INTO daily_ohlcv (stock_id, trade_date, open_price, high_price, low_price,"
                            + " close_price, volume, trading_value, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?)",
                    stockId,
                    PREV_DAY.minusDays(i),
                    closePrice,
                    closePrice.add(half),
                    closePrice.subtract(half),
                    closePrice,
                    volume,
                    NOW,
                    NOW);
        }
    }

    /** 한 경계 세트의 밴드를 가격 오름차순으로 넣는다. 각 원소는 {@code {low, high, signal_class}}. */
    static void insertBands(
            JdbcTemplate jdbcTemplate,
            long stockId,
            String horizon,
            String boundarySet,
            List<String[]> bands) {
        for (int seq = 0; seq < bands.size(); seq++) {
            String[] band = bands.get(seq);
            jdbcTemplate.update(
                    "INSERT INTO signal_price_bands (stock_id, trade_date, horizon, boundary_set, band_seq,"
                            + " price_low, price_high, signal_class, regime_suppressed, model_version,"
                            + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, FALSE, 'test', ?)",
                    stockId,
                    PREV_DAY,
                    horizon,
                    boundarySet,
                    seq,
                    band[0], // DECIMAL 컬럼 — MySQL이 문자열을 정확히 변환한다
                    band[1],
                    band[2],
                    NOW);
        }
    }
}
