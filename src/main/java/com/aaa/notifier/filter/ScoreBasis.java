package com.aaa.notifier.filter;

/**
 * {@code displayScore}의 근거 가격 (SPEC-SIGNAL-PRICECURVE-001 REQ-008/010/011, {@code Grade}·{@code
 * TransitionType}과 병렬 위치).
 *
 * <p>하류 소비자는 표시 분기를 오직 이 enum 값으로만 한다 — {@code displayScore}의 null 여부로 "커브가 있었는지"를 암묵적으로 추론하지
 * 않는다(REQ-011). 2026-10-29 운영 관측(등급은 발동가 기준, 표시 score는 전일 종가 기준이라 근거가 갈린 결함)의 재발 방지가 이 enum이 존재하는
 * 이유다.
 */
public enum ScoreBasis {
    /** 발동 체결가를 {@code signal_price_curves} 커브에서 보간한 값. */
    TRIGGER_PRICE,

    /** 장전 적재 커브가 없어 전일 종가 기준 score({@code SignalSnapshot::score})로 대체. */
    PREV_CLOSE_FALLBACK
}
