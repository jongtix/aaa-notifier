package com.aaa.notifier.stream;

/**
 * 관측치의 시장 구분 (plan.md §B.1).
 *
 * <p>시장은 <b>스트림 키에서</b> 결정한다({@link ConsumedStream#market()}) — 페이로드의 {@code trId}로 유도하지 않는다. 둘은
 * 실측상 항상 일치하지만, 어떤 스트림을 구독했는지가 상위 권위이기 때문이다.
 */
public enum Market {
    /** 국내(KRX) — `stream:tick:domestic` / `stream:signal:domestic`. */
    DOMESTIC,

    /** 해외(미국 3거래소) — `stream:tick:overseas` / `stream:signal:overseas`. */
    OVERSEAS
}
