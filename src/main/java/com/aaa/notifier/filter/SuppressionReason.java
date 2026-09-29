package com.aaa.notifier.filter;

/**
 * 전환 후보가 억제된 사유 (REQ-061 — 가드 차단/확증 미달/쿨다운/약화 강등).
 *
 * <p>{@code notification_log}에 전용 컬럼이 없으므로 DRYRUN 행에는 남지 않고, 구조화 로그와 {@code suppression_reason} 계측
 * 태그로 보완한다(REQ-062).
 */
public enum SuppressionReason {
    /** 거래량 가드 (REQ-021). */
    GUARD_VOLUME,

    /** 시간대 가드 — 개장 직후·마감 직전 (REQ-022). */
    GUARD_TIME_OF_DAY,

    /** 변동성(ATR) 과열 가드 (REQ-022). */
    GUARD_ATR,

    /** 전환 유형별 확증 횟수 미달 (REQ-033). */
    CONFIRM_PENDING,

    /** 같은 종목·horizon·Tier의 쿨다운 만료 전 (REQ-034). */
    COOLDOWN_ACTIVE,

    /** confidence 방향성 약화 추세로 HOLD 강등 (REQ-052 — Tier 구분 없이 균일 적용). */
    CONFIDENCE_WEAKENING_DEMOTED
}
