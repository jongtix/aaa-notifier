package com.aaa.notifier.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("SafeModeState — safe_mode 값 해석 (REQ-031 값 해석, design.md §2.1, AC-11 ⑤)")
class SafeModeStateTest {

    @Test
    @DisplayName("키가 없으면(null) OFF다")
    void missingKey_isOff() {
        assertThat(SafeModeState.interpret(null)).isEqualTo(SafeModeState.OFF);
        assertThat(SafeModeState.isRecognized(null)).isTrue();
    }

    @ParameterizedTest(name = "\"{0}\" → OFF")
    @ValueSource(strings = {"OFF", "off", " Off "})
    @DisplayName("OFF는 앞뒤 공백·대소문자를 무시한다")
    void off_variants(String raw) {
        assertThat(SafeModeState.interpret(raw)).isEqualTo(SafeModeState.OFF);
    }

    @ParameterizedTest(name = "\"{0}\" → AUTO")
    @ValueSource(strings = {"AUTO", "auto", " Auto\t"})
    @DisplayName("AUTO는 앞뒤 공백·대소문자를 무시한다 (자동 출처)")
    void auto_variants(String raw) {
        assertThat(SafeModeState.interpret(raw)).isEqualTo(SafeModeState.AUTO);
    }

    @ParameterizedTest(name = "\"{0}\" → ON")
    @ValueSource(strings = {"ON", "on", " On "})
    @DisplayName("ON은 앞뒤 공백·대소문자를 무시한다 (운영자 출처)")
    void on_variants(String raw) {
        assertThat(SafeModeState.interpret(raw)).isEqualTo(SafeModeState.ON);
        assertThat(SafeModeState.isRecognized(raw)).isTrue();
    }

    @ParameterizedTest(name = "\"{0}\" → ON(간주)")
    @ValueSource(strings = {"1", "true", "", "   ", "enabled", "yes"})
    @DisplayName("AC-11 ⑤ — 세 값 어디에도 해당하지 않으면 운영자 출처 ON으로 간주하고 미인식으로 표시한다")
    void unknown_isTreatedAsOperatorOn(String raw) {
        assertThat(SafeModeState.interpret(raw)).isEqualTo(SafeModeState.ON);
        assertThat(SafeModeState.isRecognized(raw)).isFalse();
    }
}
