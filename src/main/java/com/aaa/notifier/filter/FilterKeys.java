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

    /** Tier별 쿨다운 — 쿨다운 TTL로 자동 만료, 장 시작 시 초기화. */
    public static String cooldown(String symbol, String horizon, int tier) {
        return "filter:cooldown:" + symbol + ":" + horizon + ":" + tier;
    }
}
