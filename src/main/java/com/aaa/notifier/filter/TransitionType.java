package com.aaa.notifier.filter;

/**
 * 유효 등급 전환 유형 — 방향 무관 3종 (REQ-031, TECHSPEC §8.3, 설계 초안 [D-11]③ 대칭화).
 *
 * <p>확증 횟수는 직접 &lt; HOLD 이탈 &lt; HOLD 진입 순으로 엄격해지고, 쿨다운은 직접(없음) &lt; HOLD 이탈 &lt; HOLD 진입 순으로 길어진다
 * (REQ-032). 매수·매도 방향으로 파라미터를 나누지 않는다 — HOLD→BUY와 HOLD→SELL은 같은 {@link #HOLD_ENTRY}다.
 */
public enum TransitionType {
    /** 매수계 ↔ 매도계 플립 (예: BUY→SELL, STRONG_BUY→SELL). */
    DIRECT,

    /** 방향 → HOLD. */
    HOLD_EXIT,

    /** HOLD → 방향 (매수·매도 대칭). */
    HOLD_ENTRY
}
