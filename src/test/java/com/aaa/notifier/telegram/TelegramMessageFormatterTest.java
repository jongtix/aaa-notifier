package com.aaa.notifier.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TelegramMessageFormatter — 알림 본문 필드·HTML 이스케이프·길이 (REQ-011, AC-05)")
class TelegramMessageFormatterTest {

    private final TelegramMessageFormatter formatter = new TelegramMessageFormatter();

    @Test
    @DisplayName("AC-05 — 종목명(코드)·[단기 20일]·두 등급·부호 있는 백분율 예상 수익·정확도·기준가·Tier 라벨·저확신 태그가 모두 있다")
    void acFive_containsEveryField() {
        String text = formatter.formatAlert(TelegramAlerts.acFive());

        assertThat(text)
                .contains("테스트종목(BRK.B)")
                .contains("[단기 20일]")
                .contains("<b>BUY</b> → <b>STRONG_BUY</b>")
                .contains("예상 수익 -4.30%")
                .contains("정확도 0.612")
                .contains("기준가 412.5")
                .contains("Tier 1")
                .contains("저확신");
    }

    @Test
    @DisplayName("본문은 [Tier N] 종목명(코드) [horizon] / 등급 전환 / 예상 수익·정확도 / 기준가 순서로 줄을 이룬다")
    void alert_hasExpectedLineLayout() {
        String text = formatter.formatAlert(TelegramAlerts.candidate("t1"));

        assertThat(text)
                .isEqualTo(
                        "[Tier 2] 테스트종목(005930) [단기 20일]\n"
                                + "<b>HOLD</b> → <b>BUY</b>\n"
                                + "예상 수익 +4.30% · 정확도 0.712\n"
                                + "기준가 30600");
    }

    @Test
    @DisplayName("종목명이 없으면 종목코드만 표기한다")
    void blankName_fallsBackToSymbolOnly() {
        assertThat(formatter.formatAlert(TelegramAlerts.withName(null)))
                .startsWith("[Tier 2] 005930 [단기 20일]");
        assertThat(formatter.formatAlert(TelegramAlerts.withName("  ")))
                .startsWith("[Tier 2] 005930 [단기 20일]");
    }

    @Test
    @DisplayName("종목명의 HTML 예약 문자(<·>·&)를 이스케이프한다")
    void reservedCharactersInName_areEscaped() {
        String text = formatter.formatAlert(TelegramAlerts.withName("A&B <우선주>"));

        assertThat(text).contains("A&amp;B &lt;우선주&gt;(005930)").doesNotContain("<우선주>");
    }

    @Test
    @DisplayName("양수 score는 + 부호로 표기한다 (0.043 → +4.30%)")
    void positiveScore_hasPlusSign() {
        assertThat(formatter.formatAlert(TelegramAlerts.candidate("t1"))).contains("예상 수익 +4.30%");
    }

    @Test
    @DisplayName("AC-05 ① — D60은 [중기 60일]로 표기한다")
    void midTermHorizon() {
        assertThat(formatter.formatAlert(TelegramAlerts.candidate("t1", "005930", "D60")))
                .contains("[중기 60일]");
    }

    @Test
    @DisplayName("AC-05 ② — 예상 수익·정확도가 null이면 미표기 문구로 렌더링하고 예외가 없다")
    void nullSignal_rendersPlaceholder() {
        String text = formatter.formatAlert(TelegramAlerts.withoutSignal("t1"));

        assertThat(text).contains("예상 수익 미수신").contains("정확도 미수신");
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

    @Test
    @DisplayName("W2 — 요약 본문에 이관 사유별 건수 분해 줄이 있다 (safe_mode·buffer_full·rate_limit_cap 혼재)")
    void formatSummary_breaksDownByDivertReason() {
        LocalDateTime start = LocalDateTime.of(2026, 9, 29, 9, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 29, 10, 0);
        List<QueuedAlert> listed =
                List.of(
                        QueuedAlert.of(
                                TelegramAlerts.candidate("t1"), start, DivertReason.SAFE_MODE),
                        QueuedAlert.of(
                                TelegramAlerts.candidate("t2"), start, DivertReason.SAFE_MODE),
                        QueuedAlert.of(
                                TelegramAlerts.candidate("t3"), start, DivertReason.BUFFER_FULL),
                        QueuedAlert.of(
                                TelegramAlerts.candidate("t4"), start, DivertReason.RATE_LIMIT_CAP),
                        QueuedAlert.of(
                                TelegramAlerts.candidate("t5"), start, DivertReason.RATE_LIMIT_CAP),
                        QueuedAlert.of(
                                TelegramAlerts.candidate("t6"),
                                start,
                                DivertReason.RATE_LIMIT_CAP));

        String text = formatter.formatSummary(start, end, listed.size(), listed, 10);

        assertThat(text).contains("세이프모드 2").contains("버퍼 포화 1").contains("429 한도 초과 3");
    }

    @Test
    @DisplayName("W2 — 사유가 하나뿐이면 분해 줄도 그 사유 하나만 보여준다")
    void formatSummary_singleReason_showsOnlyThatReason() {
        LocalDateTime start = LocalDateTime.of(2026, 9, 29, 9, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 29, 10, 0);
        List<QueuedAlert> listed =
                List.of(
                        QueuedAlert.of(
                                TelegramAlerts.candidate("t1"),
                                start,
                                DivertReason.TRANSIENT_EXHAUSTED));

        String text = formatter.formatSummary(start, end, listed.size(), listed, 10);

        assertThat(text)
                .contains("일시 오류 소진 1")
                .doesNotContain("세이프모드")
                .doesNotContain("버퍼 포화")
                .doesNotContain("429 한도 초과")
                .doesNotContain("종료 잔류");
    }
}
