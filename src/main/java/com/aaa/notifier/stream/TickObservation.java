package com.aaa.notifier.stream;

/**
 * {@code stream:tick:*} 레코드 1건에서 유도된 관측치의 봉인 계층 (plan.md §B.1/§B.2).
 *
 * <p>구현은 정확히 둘이다 — 체결({@link TradeTickObservation}, 타입 파싱)과 호가({@link QuoteObservation}, 원시 패스스루).
 * 호가를 필드 단위로 해석하지 않는 이유는 소비 규칙이 아직 정의되지 않았기 때문이며(TECHSPEC §7은 "참고"로만 기술), 해석은 FILTER-001로 이연한다
 * (spec.md HISTORY [D-C3]).
 */
public sealed interface TickObservation permits TradeTickObservation, QuoteObservation {

    /** DB {@code stocks.symbol}과 동일 표기의 정규 종목 심볼. */
    String symbol();

    /** 엔트리 {@code symbol} 필드 원본(구독 tr_key). 해외는 {@code DNASAAPL} 형태 — 진단·역추적용. */
    String rawTrKey();

    /** 스트림 키에서 결정된 시장 구분. */
    Market market();

    /** collector가 발급해 엔트리로 전파한 추적 ID. */
    String traceId();
}
