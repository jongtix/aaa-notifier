package com.aaa.notifier.filter;

import com.aaa.notifier.stream.Market;
import com.aaa.notifier.stream.TradeTickObservation;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/** 필터 단위 테스트 공용 픽스처. */
final class FilterTestFixtures {

    /** 2026-09-29(화) 10:00 KST — 국내 정규장 중(개장 후 60분, 마감 전 330분). */
    static final Clock DOMESTIC_SESSION_CLOCK =
            Clock.fixed(
                    ZonedDateTime.of(2026, 9, 29, 10, 0, 0, 0, MarketSession.KST).toInstant(),
                    MarketSession.KST);

    /** 2026-09-29 23:00 KST = 10:00 EDT — 해외 정규장 중(개장 후 30분). */
    static final Clock OVERSEAS_SESSION_CLOCK =
            Clock.fixed(
                    ZonedDateTime.of(2026, 9, 29, 23, 0, 0, 0, MarketSession.KST).toInstant(),
                    MarketSession.KST);

    static final LocalDate PREV_DAY = LocalDate.of(2026, 9, 28);

    /** 국내 픽스처 밴드 그리드 하한·상한 (전일 종가 30,000 ±30%). */
    static final String GRID_LOW = "21000";

    static final String GRID_HIGH = "39000";

    private FilterTestFixtures() {}

    static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    static PriceBand band(String low, String high, Grade grade) {
        return new PriceBand(bd(low), bd(high), grade);
    }

    static BandPartition partition(PriceBand... bands) {
        return new BandPartition(List.of(bands));
    }

    /**
     * acceptance AC-3 형태의 밴드: PROMOTE 경계 30,500 / DEMOTE 경계 29,800 (BUY ↔ STRONG_BUY).
     *
     * <p>30,600 = 양 파티션 STRONG_BUY 일치, 30,200 = PROMOTE BUY · DEMOTE STRONG_BUY 불일치(데드존), 29,000 =
     * 양쪽 BUY.
     */
    static HorizonBands buyStrongBuyBands() {
        return new HorizonBands(
                partition(
                        band(GRID_LOW, "30490", Grade.BUY),
                        band("30500", GRID_HIGH, Grade.STRONG_BUY)),
                partition(
                        band(GRID_LOW, "29790", Grade.BUY),
                        band("29800", GRID_HIGH, Grade.STRONG_BUY)));
    }

    /** 전 가격 구간이 단일 등급인 밴드(양 파티션 일치). */
    static HorizonBands uniformBands(Grade grade) {
        return new HorizonBands(
                partition(band(GRID_LOW, GRID_HIGH, grade)),
                partition(band(GRID_LOW, GRID_HIGH, grade)));
    }

    static StockReference domesticReference(Map<String, HorizonBands> bands) {
        return new StockReference(
                1L,
                "005930",
                Market.DOMESTIC,
                PREV_DAY,
                bd("30000"),
                bd("1000000"),
                bd("300"),
                bands);
    }

    static TradeTickObservation domesticTick(String price, LocalTime time, long accumulated) {
        return new TradeTickObservation(
                "005930",
                "005930",
                Market.DOMESTIC,
                bd(price),
                10L,
                accumulated,
                time,
                null,
                "trace-" + price + "-" + accumulated);
    }

    static ReferenceDataHolder holderOf(StockReference... references) {
        ReferenceDataHolder holder = new ReferenceDataHolder();
        holder.replace(new ReferenceSnapshot(List.of(references)));
        return holder;
    }
}
