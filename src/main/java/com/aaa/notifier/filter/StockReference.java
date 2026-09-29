package com.aaa.notifier.filter;

import com.aaa.notifier.stream.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/**
 * 종목 1건의 장전 참조 데이터 (REQ-001) — 틱 경로는 이 값만 읽는다(REQ-002, DB 동기 조회 금지).
 *
 * @param stockId {@code stocks.id} — DRYRUN INSERT의 {@code stock_id}로 재사용한다(신규 SELECT 없음)
 * @param symbol 정규 종목 심볼 ({@code stocks.symbol})
 * @param market 시장 구분
 * @param prevTradeDate 전일(직전 거래일) 일봉 날짜 — 밴드 기준일과 같아야 한다
 * @param prevClose 전일 종가
 * @param averageVolume 최근 20거래일 평균 일 거래량 (거래량 가드 입력)
 * @param atr ATR(14). 일봉 이력이 15개 미만이면 {@code null} — ATR 가드는 산출 불가를 억제로 다룬다
 * @param bands horizon({@code "D20"}/{@code "D60"}) → 이원 경계 파티션 쌍
 */
public record StockReference(
        long stockId,
        String symbol,
        Market market,
        LocalDate prevTradeDate,
        BigDecimal prevClose,
        BigDecimal averageVolume,
        BigDecimal atr,
        Map<String, HorizonBands> bands) {

    public StockReference {
        Objects.requireNonNull(symbol, "symbol must not be null");
        Objects.requireNonNull(market, "market must not be null");
        Objects.requireNonNull(prevTradeDate, "prevTradeDate must not be null");
        Objects.requireNonNull(prevClose, "prevClose must not be null");
        bands = Map.copyOf(bands);
    }
}
