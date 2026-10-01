package com.aaa.notifier.filter;

/**
 * 전환 매트릭스 1칸의 분류 결과 (REQ-041/042).
 *
 * @param tier Tier 라벨 1/2/3
 * @param type 전환 유형. 순수 강도 강화·강도 완화 칸은 {@code null}
 * @param kind 유지 시간·쿨다운 파라미터를 고르는 기준
 */
public record TransitionClass(int tier, TransitionType type, Kind kind) {

    /** 칸의 파라미터 출처. */
    public enum Kind {
        /**
         * 3종 전환 유형(직접/HOLD 이탈/HOLD 진입) — ※칸 포함, 그 유형의 유지 시간·쿨다운을 쓴다(REQ-042). HOLD 진입 ※칸만 전용 유지 시간
         * 유형(STRONG 도착)을 쓴다(SPEC-NOTIFIER-FILTER-002 REQ-008).
         */
        TYPED,

        /**
         * 순수 강도 강화(BUY→STRONG_BUY 등) — Tier 1, 순수 강도 강화 유지 시간 + Tier-1 동일방향 반복 쿨다운(REQ-035,
         * FILTER-002).
         */
        STRENGTHEN,

        /** 강도 완화(STRONG_BUY→BUY 등) — Tier 3, 강도 완화 유지 시간 + Tier 3 전용 쿨다운(FILTER-002). */
        WEAKEN
    }
}
