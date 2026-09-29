package com.aaa.notifier.filter;

import com.aaa.notifier.filter.ReferenceRows.BandRow;
import com.aaa.notifier.filter.ReferenceRows.OhlcvRow;
import com.aaa.notifier.filter.ReferenceRows.StockRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * 적재 행 → 종목 참조 데이터 조립 (순수 계산, DB 접근 없음).
 *
 * <p><b>밴드 선택 규칙.</b> {@code signal_price_bands.trade_date}는 "기준 일봉 날짜"이고 밴드는 그 익거래일 장중에 적용된다(V44
 * DDL 주석). 그래서 종목별 직전 일봉 날짜(= 전일 종가 날짜)와 기준일이 <b>같은</b> 밴드만 쓴다. 스윕이 그날 그 종목을 건너뛰었다면 묵은 밴드를 쓰지 않고 해당
 * horizon을 비워 둔다 — 묵은 밴드는 다른 전일 종가를 중심으로 계산된 그리드라 판정이 어긋난다.
 */
@Slf4j
final class ReferenceAssembler {

    /** 평균 거래량 산출 창 — "20거래일 평균 거래량"(REQ-021). */
    private static final int VOLUME_WINDOW = 20;

    /** ATR 기간 — ATR(14)(REQ-022). 진폭(TR)은 전일 종가가 필요하므로 일봉이 기간+1개 있어야 한다. */
    private static final int ATR_PERIOD = 14;

    private static final int DECIMAL_SCALE = 4;

    private static final String PROMOTE = "PROMOTE";
    private static final String DEMOTE = "DEMOTE";

    private ReferenceAssembler() {}

    /**
     * 종목 1건의 참조 데이터를 조립한다.
     *
     * @param today 시장 현지 '당일' — 이 날짜 미만의 일봉만 이력으로 본다(당일 부분 일봉 배제)
     * @return 이력이 없으면 빈 값
     */
    static Optional<StockReference> assemble(
            StockRow stock, LocalDate today, List<OhlcvRow> rows, List<BandRow> bandRows) {
        List<OhlcvRow> history =
                rows.stream()
                        .filter(row -> row.tradeDate().isBefore(today))
                        .sorted(Comparator.comparing(OhlcvRow::tradeDate))
                        .toList();
        if (history.isEmpty()) {
            return Optional.empty();
        }
        OhlcvRow previous = history.getLast();
        return Optional.of(
                new StockReference(
                        stock.id(),
                        stock.symbol(),
                        stock.market(),
                        previous.tradeDate(),
                        previous.close(),
                        averageVolume(history),
                        atr(history),
                        horizonBands(stock.symbol(), previous.tradeDate(), bandRows)));
    }

    private static BigDecimal averageVolume(List<OhlcvRow> history) {
        List<OhlcvRow> window =
                history.subList(Math.max(0, history.size() - VOLUME_WINDOW), history.size());
        long total = window.stream().mapToLong(OhlcvRow::volume).sum();
        return BigDecimal.valueOf(total)
                .divide(BigDecimal.valueOf(window.size()), DECIMAL_SCALE, RoundingMode.HALF_UP);
    }

    /** ATR(14) — 최근 14일 진폭(TR)의 단순 평균. 이력이 15일 미만이면 {@code null}. */
    private static BigDecimal atr(List<OhlcvRow> history) {
        if (history.size() < ATR_PERIOD + 1) {
            return null;
        }
        List<OhlcvRow> window = history.subList(history.size() - (ATR_PERIOD + 1), history.size());
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 1; i < window.size(); i++) {
            sum = sum.add(trueRange(window.get(i), window.get(i - 1).close()));
        }
        return sum.divide(BigDecimal.valueOf(ATR_PERIOD), DECIMAL_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal trueRange(OhlcvRow day, BigDecimal previousClose) {
        BigDecimal range = day.high().subtract(day.low());
        BigDecimal upGap = day.high().subtract(previousClose).abs();
        BigDecimal downGap = day.low().subtract(previousClose).abs();
        return range.max(upGap).max(downGap);
    }

    private static Map<String, HorizonBands> horizonBands(
            String symbol, LocalDate baseDate, List<BandRow> bandRows) {
        Map<String, Map<String, List<BandRow>>> byHorizon =
                bandRows.stream()
                        .filter(row -> row.tradeDate().equals(baseDate))
                        .collect(
                                Collectors.groupingBy(
                                        BandRow::horizon,
                                        Collectors.groupingBy(BandRow::boundarySet)));
        return byHorizon.entrySet().stream()
                .flatMap(
                        entry ->
                                toHorizonBands(symbol, entry.getKey(), entry.getValue())
                                        .map(bands -> Map.entry(entry.getKey(), bands))
                                        .stream())
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static Optional<HorizonBands> toHorizonBands(
            String symbol, String horizon, Map<String, List<BandRow>> sets) {
        Optional<BandPartition> promote = toPartition(sets.get(PROMOTE));
        Optional<BandPartition> demote = toPartition(sets.get(DEMOTE));
        if (promote.isEmpty() || demote.isEmpty()) {
            log.warn(
                    "[filter-reference] 이원 경계 파티션 불완전 — horizon 제외 symbol={} horizon={} sets={}",
                    symbol,
                    horizon,
                    sets.keySet());
            return Optional.empty();
        }
        return Optional.of(new HorizonBands(promote.get(), demote.get()));
    }

    private static Optional<BandPartition> toPartition(List<BandRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return Optional.empty();
        }
        List<Optional<PriceBand>> parsed =
                rows.stream()
                        .sorted(Comparator.comparing(BandRow::low))
                        .map(
                                row ->
                                        Grade.parse(row.signalClass())
                                                .map(
                                                        grade ->
                                                                new PriceBand(
                                                                        row.low(),
                                                                        row.high(),
                                                                        grade)))
                        .toList();
        // 등급 하나라도 해석 불가면 파티션이 불완전하다 — 반쪽 파티션으로 판정하지 않는다
        if (parsed.stream().anyMatch(Optional::isEmpty)) {
            return Optional.empty();
        }
        return Optional.of(new BandPartition(parsed.stream().map(Optional::orElseThrow).toList()));
    }
}
