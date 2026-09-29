package com.aaa.notifier.filter;

import java.util.Optional;

/**
 * 5클래스 등급 (analyzer {@code signal_class} 도메인과 동일 표기).
 *
 * <p>선언 순서가 매수 강도 순서(강매수 → 강매도)다 — 전환 매트릭스(REQ-041)의 행·열 순서와 같다.
 */
public enum Grade {
    STRONG_BUY,
    BUY,
    HOLD,
    SELL,
    STRONG_SELL;

    /**
     * DB·스트림의 등급 문자열을 해석한다.
     *
     * @param raw {@code signal_class} 원문
     * @return 알 수 없는 값이면 빈 값 — 호출자가 해당 행을 건너뛴다(예외로 배치 전체를 깨뜨리지 않는다)
     */
    public static Optional<Grade> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        for (Grade grade : values()) {
            if (grade.name().equals(raw)) {
                return Optional.of(grade);
            }
        }
        return Optional.empty();
    }
}
