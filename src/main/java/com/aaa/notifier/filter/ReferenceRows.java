package com.aaa.notifier.filter;

import com.aaa.notifier.stream.Market;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 장전 적재 SELECT 3종의 행 타입 — 적재기({@link ReferenceDataLoader})와 조립기({@link ReferenceAssembler})의 경계.
 */
interface ReferenceRows {

    /** {@code stocks} 1행. {@code market}이 미지원 값이면 {@code null}. */
    record StockRow(long id, String symbol, Market market) {}

    /** {@code daily_ohlcv} 1행 (참조 산출에 필요한 컬럼만). */
    record OhlcvRow(
            long stockId,
            LocalDate tradeDate,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            long volume) {}

    /** {@code signal_price_bands} 1행. */
    record BandRow(
            long stockId,
            LocalDate tradeDate,
            String horizon,
            String boundarySet,
            BigDecimal low,
            BigDecimal high,
            String signalClass) {}
}
