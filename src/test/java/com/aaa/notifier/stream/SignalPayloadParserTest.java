package com.aaa.notifier.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 신호 페이로드 파서 테스트 (REQ-NOTIFIER-CONSUMER-041, AC-15).
 *
 * <p>계약 원천은 analyzer {@code inference/publish.py}의 {@code SIGNAL_STREAM_FIELDS} 7필드다. 7필드 중 하나라도
 * 없으면 해석 불가로 판정해 AC-10 경로(즉시 DLQ)로 보낸다.
 */
@DisplayName("SignalPayloadParser — 신호 7필드 계약 (REQ-041, AC-15)")
class SignalPayloadParserTest {

    private final SignalPayloadParser parser = new SignalPayloadParser();

    @Nested
    @DisplayName("정상 7필드 엔트리")
    class WellFormedEntry {

        @Test
        @DisplayName("① horizon이 D20 문자열 그대로 보존된다 (enum 변환·대소문자 변경 없음)")
        void horizon_isPreservedVerbatim() {
            SignalObservation signal = parser.parse(KisFrameFixtures.signalEntry());

            assertThat(signal.horizon()).isEqualTo("D20");
        }

        @Test
        @DisplayName("② score·confidence가 BigDecimal로 정밀도 손실 없이 파싱된다")
        void scoreAndConfidence_parseAsBigDecimal() {
            SignalObservation signal = parser.parse(KisFrameFixtures.signalEntry());

            assertThat(signal.score()).isEqualTo(new BigDecimal("0.043"));
            assertThat(signal.confidence()).isEqualTo(new BigDecimal("0.71"));
        }

        @Test
        @DisplayName("③ trade_date가 LocalDate로 파싱된다")
        void tradeDate_parsesAsLocalDate() {
            SignalObservation signal = parser.parse(KisFrameFixtures.signalEntry());

            assertThat(signal.tradeDate()).isEqualTo(LocalDate.of(2026, 9, 18));
        }

        @Test
        @DisplayName("나머지 필드(symbol·trace_id·signal_class)도 그대로 보존된다")
        void remainingFields_arePreserved() {
            SignalObservation signal = parser.parse(KisFrameFixtures.signalEntry());

            assertThat(signal.symbol()).isEqualTo("AAPL");
            assertThat(signal.traceId()).isEqualTo(KisFrameFixtures.TRACE_ID);
            assertThat(signal.signalClass()).isEqualTo("STRONG_BUY");
        }

        @Test
        @DisplayName("D60 horizon도 무변환 보존된다")
        void horizonD60_isPreservedVerbatim() {
            Map<String, String> entry = replace(KisFrameFixtures.signalEntry(), "horizon", "D60");

            assertThat(parser.parse(entry).horizon()).isEqualTo("D60");
        }
    }

    @Nested
    @DisplayName("AC-15 단언 ④ — 7필드 중 하나라도 없으면 해석 불가")
    class MissingField {

        @ParameterizedTest(name = "{0} 누락")
        @ValueSource(
                strings = {
                    "symbol",
                    "horizon",
                    "trade_date",
                    "trace_id",
                    "signal_class",
                    "score",
                    "confidence"
                })
        @DisplayName("7필드 각각의 누락이 모두 해석 불가로 판정된다")
        void anyMissingField_isUnparseable(String missingField) {
            Map<String, String> entry =
                    KisFrameFixtures.without(KisFrameFixtures.signalEntry(), missingField);

            assertThatThrownBy(() -> parser.parse(entry))
                    .isInstanceOf(PayloadUnparseableException.class)
                    .hasMessageContaining(missingField);
        }
    }

    @Nested
    @DisplayName("값 형식 오류")
    class MalformedValue {

        @Test
        @DisplayName("trade_date가 ISO 8601이 아니면 해석 불가다")
        void malformedTradeDate_isUnparseable() {
            Map<String, String> entry =
                    replace(KisFrameFixtures.signalEntry(), "trade_date", "2026/09/18");

            assertThatThrownBy(() -> parser.parse(entry))
                    .isInstanceOf(PayloadUnparseableException.class);
        }

        @Test
        @DisplayName("score가 숫자가 아니면 해석 불가다 (예외 삼킴 금지)")
        void nonNumericScore_isUnparseable() {
            Map<String, String> entry = replace(KisFrameFixtures.signalEntry(), "score", "N/A");

            assertThatThrownBy(() -> parser.parse(entry))
                    .isInstanceOf(PayloadUnparseableException.class);
        }

        @Test
        @DisplayName("confidence가 숫자가 아니면 해석 불가다")
        void nonNumericConfidence_isUnparseable() {
            Map<String, String> entry = replace(KisFrameFixtures.signalEntry(), "confidence", "높음");

            assertThatThrownBy(() -> parser.parse(entry))
                    .isInstanceOf(PayloadUnparseableException.class);
        }
    }

    private static Map<String, String> replace(
            Map<String, String> entry, String key, String value) {
        return KisFrameFixtures.replace(entry, key, value);
    }
}
