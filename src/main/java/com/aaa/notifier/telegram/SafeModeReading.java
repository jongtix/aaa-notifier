package com.aaa.notifier.telegram;

/**
 * safe_mode 1회 읽기 결과.
 *
 * @param state 해석 상태
 * @param raw Redis에서 읽은 원래 문자열 (키 없음 = {@code null}) — 조건부 쓰기의 기대값으로 쓴다
 * @param readFailed 읽기 실패로 {@code OFF}를 간주했는지
 */
public record SafeModeReading(SafeModeState state, String raw, boolean readFailed) {

    static SafeModeReading of(String raw) {
        return new SafeModeReading(SafeModeState.interpret(raw), raw, false);
    }

    static SafeModeReading failed() {
        return new SafeModeReading(SafeModeState.OFF, null, true);
    }
}
