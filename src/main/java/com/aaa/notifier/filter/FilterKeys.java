package com.aaa.notifier.filter;

/**
 * {@code filter:*} Redis 키 패턴 (design.md §2).
 *
 * <p>모든 키는 예외 없이 {@code {종목코드}:{horizon}} 두 차원을 포함한다(REQ-013 — horizon 독립, 설계 초안 [D-7] Option A).
 * {@code filter:hysteresis}·{@code filter:timing} 키는 두지 않는다(REQ-011 후단, TECHSPEC §8.2).
 */
public final class FilterKeys {

    private FilterKeys() {}

    /** 유효 등급 — 장 마감 만료. */
    public static String grade(String symbol, String horizon) {
        return "filter:grade:" + symbol + ":" + horizon;
    }

    /** 당일 신호 스냅샷 — 다음 신호 도착까지(다일). */
    public static String signal(String symbol, String horizon) {
        return "filter:signal:" + symbol + ":" + horizon;
    }

    /** 확증 대기 후보 — 장 마감 만료. */
    public static String confirm(String symbol, String horizon) {
        return "filter:confirm:" + symbol + ":" + horizon;
    }

    /**
     * 대기 억제 방출 이력 — Set, 멤버 {@link #recordedMember}, 장 마감 만료 (SPEC-NOTIFIER-FILTER-002 REQ-014,
     * design.md §4).
     */
    public static String recorded(String symbol, String horizon) {
        return "filter:recorded:" + symbol + ":" + horizon;
    }

    /** 방출 이력 멤버 {@code {기준 등급}>{후보 등급}:{억제 사유}} — 종결 시 사유 enum으로 이름을 만들어 지운다(패턴 스캔 없음). */
    public static String recordedMember(Grade from, Grade to, SuppressionReason reason) {
        return from.name() + ">" + to.name() + ":" + reason.name();
    }

    /** Tier별 쿨다운 — 쿨다운 TTL로 자동 만료, 장 시작 시 초기화. */
    public static String cooldown(String symbol, String horizon, int tier) {
        return "filter:cooldown:" + symbol + ":" + horizon + ":" + tier;
    }

    /** confidence 회전 윈도 — 다일 수명(만료 없음, REQ-051 버그 수정). */
    public static String confidence(String symbol, String horizon) {
        return "filter:confidence:" + symbol + ":" + horizon;
    }
}
