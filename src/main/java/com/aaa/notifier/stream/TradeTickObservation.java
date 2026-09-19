package com.aaa.notifier.stream;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Objects;

/**
 * 체결 관측치 (REQ-NOTIFIER-CONSUMER-033/034/051, plan.md §B.1).
 *
 * <p><b>체결가는 {@code BigDecimal}이며 자리수(scale)를 보존한다.</b> 국내는 정수 원화({@code 313000}), 해외는 소수
 * 4자리({@code 298.4700})로 도착하는데, {@code double}은 밴드 경계 비교에서 오판 위험이 있고 {@code stripTrailingZeros()}를
 * 거치면 {@code 298.4700}의 scale이 4에서 2로 줄어 {@link #priceScale()} 대조가 무의미해진다.
 *
 * <p><b>{@link #priceScale()}(해외 {@code ZDIV}, 국내 {@code null})는 검증용 메타데이터이지 배율 지수가 아니다</b>(spec.md
 * HISTORY [D-C4]). {@code LAST}는 이미 {@code ZDIV} 자리수로 소수점이 찍힌 상태로 도착하므로 {@code 10^ZDIV} 배율 연산은 이중
 * 스케일링이 되어 값을 오염시킨다. 소비 계층은 배율 연산을 하지 않는다.
 *
 * <p><b>하류 재검출 의무(REQ-051 후단)</b>: 체결가와 {@code priceScale}이 <b>함께</b> 실리는 것이 하류가 의심 값을 재검출할 수 있는
 * 유일한 수단이다. 체결가를 밴드 판정·임계 비교 등 수치 판단에 쓰기 전에 {@code price().scale() == priceScale()} 성립 여부를 확인하고,
 * 불일치하면 그 관측치를 수치 판단에서 제외하거나 의심 값으로 표시해야 한다. 소비 계층은 값을 보정하지 않는다(REQ-034). {@code priceScale}이
 * {@code null}인 국내 체결은 이 확인의 대상이 아니다.
 *
 * @param symbol 정규 종목 심볼 (국내 {@code [0]}, 해외 {@code [1]} {@code SYMB} — 접두사 절단 유도 금지)
 * @param rawTrKey 엔트리 {@code symbol} 필드 원본
 * @param market 시장 구분
 * @param price 체결가 (국내 {@code [2]} {@code STCK_PRPR}, 해외 {@code [11]} {@code LAST}) — 자리수 보존
 * @param volume 체결량 (국내 {@code [12]} {@code CNTG_VOL}, 해외 {@code [19]} {@code EVOL})
 * @param accumulatedVolume 누적 거래량 (국내 {@code [13]} {@code ACML_VOL}, 해외 {@code [20]} {@code TVOL})
 * @param tradeTime 체결 시각 (국내 {@code [1]} {@code STCK_CNTG_HOUR}, 해외 {@code [7]} {@code KHMS} — 둘 다
 *     KST. 해외는 {@code XHMS}(ET)가 아니다)
 * @param priceScale 해외 {@code [2]} {@code ZDIV} 정수값, 국내는 {@code null}. 판독 불가 시에도 {@code null}
 * @param traceId 엔트리에서 승계한 추적 ID
 */
public record TradeTickObservation(
        String symbol,
        String rawTrKey,
        Market market,
        BigDecimal price,
        long volume,
        long accumulatedVolume,
        LocalTime tradeTime,
        Integer priceScale,
        String traceId)
        implements TickObservation {

    /** 하류가 의존하는 필수 항목의 부재를 생성 시점에 차단한다 ({@code priceScale}·{@code traceId}는 null 허용). */
    public TradeTickObservation {
        Objects.requireNonNull(symbol, "symbol must not be null");
        Objects.requireNonNull(rawTrKey, "rawTrKey must not be null");
        Objects.requireNonNull(market, "market must not be null");
        Objects.requireNonNull(price, "price must not be null");
        Objects.requireNonNull(tradeTime, "tradeTime must not be null");
    }
}
