package com.aaa.notifier.stream;

import java.util.Objects;

/**
 * 호가 패스스루 관측치 (REQ-NOTIFIER-CONSUMER-032, plan.md §B.2).
 *
 * <p>호가 레코드(국내 {@code H0STASP0} 63필드, 2026-09-22 정정 — 원안 62필드 / 해외 {@code HDFSASP0} 71필드)는 <b>필드
 * 단위로 해석하지 않는다</b> — 소비 규칙이 아직 정의되지 않았기 때문이며(spec.md HISTORY [D-C3]), {@link #rawRecord()}에 원시 문자열을
 * 그대로 실어 FILTER-001로 넘긴다.
 *
 * <p>정규 심볼은 레코드 선두(국내 {@code [0]}, 해외 {@code [1]})에서 취한다 — 체결 레코드와 동일한 심볼 위치 규칙이며, 호가 고유 필드(레벨별
 * 호가·잔량)를 해석하는 것이 아니다.
 *
 * <p>배치 분할(REQ-032)은 호가에도 동일하게 적용한다. 실측상 호가는 항상 {@code count=001}이지만 그것은 관측이지 계약이 아니다.
 *
 * @param symbol 정규 종목 심볼
 * @param rawTrKey 엔트리 {@code symbol} 필드 원본
 * @param market 시장 구분
 * @param transactionId KIS 실시간 트랜잭션 ID ({@code H0STASP0} 또는 {@code HDFSASP0})
 * @param rawRecord 레코드 1건의 원시 {@code ^} 구분 문자열 (입력과 바이트 동등)
 * @param traceId 엔트리에서 승계한 추적 ID
 */
public record QuoteObservation(
        String symbol,
        String rawTrKey,
        Market market,
        String transactionId,
        String rawRecord,
        String traceId)
        implements TickObservation {

    /** 하류가 의존하는 필수 항목의 부재를 생성 시점에 차단한다 ({@code traceId}는 null 허용). */
    public QuoteObservation {
        Objects.requireNonNull(symbol, "symbol must not be null");
        Objects.requireNonNull(rawTrKey, "rawTrKey must not be null");
        Objects.requireNonNull(market, "market must not be null");
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(rawRecord, "rawRecord must not be null");
    }
}
