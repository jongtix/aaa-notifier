package com.aaa.notifier.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TelegramMessageFormatter — 알림 본문 필드·HTML 이스케이프·길이 (REQ-011, AC-05)")
class TelegramMessageFormatterTest {

    private final TelegramMessageFormatter formatter = new TelegramMessageFormatter();

    @Test
    @DisplayName("AC-05 — 심볼·[단기]·두 등급·부호 있는 백분율 score·confidence·발동가·Tier 라벨·저확신 태그가 모두 있다")
    void acFive_containsEveryField() {
        String text = formatter.formatAlert(TelegramAlerts.acFive());

        assertThat(text)
                .contains("BRK.B")
                .contains("[단기]")
                .contains("<b>BUY</b> → <b>STRONG_BUY</b>")
                .contains("-4.30%")
                .contains("0.612")
                .contains("412.5")
                .contains("Tier 1")
                .contains("저확신");
    }

    @Test
    @DisplayName("양수 score는 + 부호로 표기한다 (0.043 → +4.30%)")
    void positiveScore_hasPlusSign() {
        assertThat(formatter.formatAlert(TelegramAlerts.candidate("t1"))).contains("+4.30%");
    }

    @Test
    @DisplayName("AC-05 ① — D60은 [중기]로 표기한다")
    void midTermHorizon() {
        assertThat(formatter.formatAlert(TelegramAlerts.candidate("t1", "005930", "D60")))
                .contains("[중기]");
    }

    @Test
    @DisplayName("AC-05 ② — score·confidence가 null이면 미표기 문구로 렌더링하고 예외가 없다")
    void nullSignal_rendersPlaceholder() {
        String text = formatter.formatAlert(TelegramAlerts.withoutSignal("t1"));

        assertThat(text).contains("score 미수신").contains("confidence 미수신");
    }

    @Test
    @DisplayName("저확신 표시가 꺼져 있으면 태그를 붙이지 않는다")
    void noLowConfidenceTag_whenFlagOff() {
        assertThat(formatter.formatAlert(TelegramAlerts.candidate("t1"))).doesNotContain("저확신");
    }

    @Test
    @DisplayName("AC-05 ③ — HTML 예약 문자(<·>·&)가 든 심볼을 이스케이프한다")
    void reservedCharacters_areEscaped() {
        String text = formatter.formatAlert(TelegramAlerts.withSymbol("A<B>&C"));

        assertThat(text).contains("A&lt;B&gt;&amp;C").doesNotContain("A<B>");
    }

    @Test
    @DisplayName("텔레그램이 모르는 이름 참조(&eacute; 등)를 만들지 않는다 — 비ASCII 문자는 그대로 둔다")
    void nonAscii_isNotTurnedIntoNamedEntities() {
        String text = formatter.formatAlert(TelegramAlerts.withSymbol("CAFÉ·1"));

        assertThat(text).contains("CAFÉ·1").doesNotContain("&eacute;").doesNotContain("&middot;");
    }

    @Test
    @DisplayName("AC-05 ④ — 본문 길이는 4096자 이내로 유지된다")
    void longBody_isCappedAt4096() {
        String text = formatter.formatAlert(TelegramAlerts.withSymbol("X".repeat(5000)));

        assertThat(text).hasSizeLessThanOrEqualTo(4096);
    }
}
