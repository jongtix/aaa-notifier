package com.aaa.notifier.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.notifier.filter.AlertDecision;
import com.aaa.notifier.filter.ScoreBasis;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
                .contains("예상 수익(현재가 기준) -4.30%")
                .contains("정확도(전일 종가 기준) 0.612")
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
                                + "예상 수익(현재가 기준) +4.30% · 정확도(전일 종가 기준) 0.712\n"
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
        assertThat(formatter.formatAlert(TelegramAlerts.candidate("t1")))
                .contains("예상 수익(현재가 기준) +4.30%");
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

    @Nested
    @DisplayName("displayScore/scoreBasis 라벨 (SPEC-SIGNAL-PRICECURVE-001 REQ-011/012/013/014/015)")
    class ScoreBasisLabels {

        @Test
        @DisplayName(
                "AC-013 — TRIGGER_PRICE와 PREV_CLOSE_FALLBACK 라벨은 서로 다른 문자열이고, 폴백 라벨은 \"전일\" 또는"
                        + " \"종가\" 형태소를 포함한다")
        void fallbackLabel_differsFromTriggerPriceLabel_andDisclosesBasis() {
            String triggerPriceText =
                    TelegramMessageFormatter.formatDisplayScore(
                            new BigDecimal("0.0430"), ScoreBasis.TRIGGER_PRICE);
            String fallbackText =
                    TelegramMessageFormatter.formatDisplayScore(
                            new BigDecimal("0.0430"), ScoreBasis.PREV_CLOSE_FALLBACK);

            assertThat(fallbackText).isNotEqualTo(triggerPriceText);
            assertThat(fallbackText).containsAnyOf("전일", "종가");
        }

        @Test
        @DisplayName("AC-012 — TRIGGER_PRICE 라벨은 발동가/현재가 기준임을 알린다")
        void triggerPriceLabel_disclosesCurrentPriceBasis() {
            String text =
                    TelegramMessageFormatter.formatDisplayScore(
                            new BigDecimal("0.0430"), ScoreBasis.TRIGGER_PRICE);

            assertThat(text).contains("현재가");
        }

        @Test
        @DisplayName("AC-011 — displayScore가 null이면(신호 미수신) scoreBasis와 무관하게 기존 미수신 문구다")
        void nullDisplayScore_rendersPlaceholderRegardlessOfBasis() {
            assertThat(TelegramMessageFormatter.formatDisplayScore(null, ScoreBasis.TRIGGER_PRICE))
                    .isEqualTo("예상 수익 미수신");
            assertThat(
                            TelegramMessageFormatter.formatDisplayScore(
                                    null, ScoreBasis.PREV_CLOSE_FALLBACK))
                    .isEqualTo("예상 수익 미수신");
        }

        @Test
        @DisplayName("AC-014 — confidence 라벨은 항상 전일 종가 기준을 명시하고, TRIGGER_PRICE score 라벨과 문자열이 다르다")
        void confidenceLabel_alwaysDisclosesPrevCloseBasis_andDiffersFromScoreLabel() {
            // candidate()는 scoreBasis=TRIGGER_PRICE — score 라벨은 "현재가 기준", confidence 라벨은 항상
            // "전일 종가 기준"이라 둘은 같은 근거를 공유하는 것처럼 보이지 않아야 한다(REQ-014).
            String text = formatter.formatAlert(TelegramAlerts.candidate("t-ac14"));
            String[] lines = text.split("\n");
            String[] scoreAndConfidence = lines[2].split(" · ");

            assertThat(scoreAndConfidence[0]).contains("현재가 기준");
            assertThat(scoreAndConfidence[1]).contains("전일 종가 기준");
            assertThat(scoreAndConfidence[0]).isNotEqualTo(scoreAndConfidence[1]);
        }

        @Test
        @DisplayName("AC-012/015 — formatAlert()와 formatSummary()는 같은 scoreBasis에 같은 라벨 문자열을 쓴다")
        void formatAlertAndFormatSummary_shareSameLabelForSameScoreBasis() {
            AlertDecision decision = TelegramAlerts.candidate("t-shared");
            LocalDateTime now = LocalDateTime.of(2026, 9, 29, 10, 0);

            String alertText = formatter.formatAlert(decision);
            String summaryText =
                    formatter.formatSummary(
                            now,
                            now,
                            1,
                            List.of(QueuedAlert.of(decision, now, DivertReason.SAFE_MODE)),
                            10);

            String basisLabel =
                    decision.scoreBasis() == ScoreBasis.TRIGGER_PRICE ? "현재가 기준" : "전일 종가 기준";
            assertThat(alertText).contains("예상 수익(" + basisLabel + ")");
            assertThat(summaryText).contains("예상 수익(" + basisLabel + ")");
        }

        @Test
        @DisplayName(
                "AC-015 — QueuedAlert.of()는 AlertDecision의 displayScore/scoreBasis를 정보 손실 없이 옮긴다")
        void queuedAlertOf_carriesDisplayScoreAndScoreBasisWithoutLoss() {
            AlertDecision decision = TelegramAlerts.candidate("t-ac15");

            QueuedAlert queued =
                    QueuedAlert.of(
                            decision, LocalDateTime.of(2026, 9, 29, 10, 0), DivertReason.SAFE_MODE);

            assertThat(queued.displayScore()).isEqualByComparingTo(decision.displayScore());
            assertThat(queued.scoreBasis()).isEqualTo(decision.scoreBasis());
        }
    }
}
