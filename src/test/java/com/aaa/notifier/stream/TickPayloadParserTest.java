package com.aaa.notifier.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 틱 페이로드 파서 테스트 — 엔트리 4필드 계약·배치 분할·체결 필드 추출·해외 심볼 정규화 (REQ-NOTIFIER-CONSUMER-031/032/033,
 * AC-12/AC-13/AC-14 시나리오 A).
 *
 * <p>AC-14 시나리오 B({@code ZDIV} WARN 폴백 3조건)는 Given이 다르므로 {@link TickPayloadParserZdivFallbackTest}로
 * 분리한다(acceptance.md AC-14 서두 규정).
 */
@DisplayName("TickPayloadParser — 틱 페이로드 계약 (REQ-031/032/033, AC-12~14A)")
class TickPayloadParserTest {

    private final TickPayloadParser parser = new TickPayloadParser();

    @Nested
    @DisplayName("실측 프레임 필드 수 고정 (plan.md §D M1)")
    class MeasuredFrameContract {

        @Test
        @DisplayName("ws-01 국내 체결 레코드는 47필드이고 [44]는 빈 값, [46]은 2026-09-22 정정 트레일링 필드다 (엣지케이스 E1)")
        void domesticRecord_has47Fields_withEmptyIndex44AndCorrectedTrailingField() {
            String[] fields = KisFrameFixtures.split(KisFrameFixtures.DOMESTIC_TRADE_RECORD);

            assertThat(fields).hasSize(KisFrameFixtures.DOMESTIC_TRADE_FIELD_COUNT);
            assertThat(fields[44]).isEmpty();
            assertThat(fields[45]).isEqualTo("322000");
            assertThat(fields[46]).isEqualTo("2");
        }

        @Test
        @DisplayName("ws-03 해외 체결 레코드는 26필드이고 [1]=SYMB, [2]=ZDIV다")
        void overseasRecord_has26Fields_withSymbAndZdiv() {
            String[] fields = KisFrameFixtures.split(KisFrameFixtures.OVERSEAS_TRADE_RECORD);

            assertThat(fields).hasSize(KisFrameFixtures.OVERSEAS_TRADE_FIELD_COUNT);
            assertThat(fields[1]).isEqualTo("AAPL");
            assertThat(fields[2]).isEqualTo("4");
        }
    }

    /**
     * 국내 tick 페이로드 실측 필드 수 정정 회귀 테스트 (2026-09-22).
     *
     * <p>프로덕션 DLQ({@code stream:dlq:stream:tick:domestic}) 원본 페이로드를 실측한 결과 {@code H0STCNT0}는 46이 아닌
     * 47필드, {@code H0STASP0}는 62가 아닌 63필드였다 — 이 불일치로 국내 tick/quote 트래픽 전량이 배치 분할 나눗셈 단계에서 {@link
     * PayloadUnparseableException}로 DLQ 이관되고 있었다. 필드 인덱스 매핑({@code TradeFieldLayout.DOMESTIC})은
     * index 0~13만 참조하므로 정정 전에도 깨지지 않았다 — 깨진 것은 {@code TickTransactionId.fieldsPerRecord} 나눗셈 상수뿐이다.
     */
    @Nested
    @DisplayName("국내 실측 필드 수 정정 회귀 (2026-09-22 — 46/62 → 47/63)")
    class FieldCountCorrectionRegression {

        @Test
        @DisplayName("국내 체결 47필드 레코드가 정상 파싱되고 체결가·체결량·누적거래량·체결시각·심볼이 실측값과 일치한다")
        void domesticTrade_parsesWith47FieldRecord_andExtractsMeasuredValues() {
            Map<String, String> entry =
                    KisFrameFixtures.tickEntry(
                            "005930",
                            "H0STCNT0",
                            KisFrameFixtures.DOMESTIC_TRADE_RECORD,
                            KisFrameFixtures.TRACE_ID);

            List<TickObservation> observations = parser.parse(ConsumedStream.TICK_DOMESTIC, entry);

            assertThat(observations).hasSize(1);
            TradeTickObservation trade = tradesOf(observations).getFirst();
            assertThat(trade)
                    .extracting(
                            TradeTickObservation::symbol,
                            TradeTickObservation::price,
                            TradeTickObservation::volume,
                            TradeTickObservation::accumulatedVolume,
                            TradeTickObservation::tradeTime)
                    .containsExactly(
                            "005930",
                            new BigDecimal("313000"),
                            825L,
                            34_502_265L,
                            LocalTime.of(15, 14, 25));
        }

        @Test
        @DisplayName("국내 호가 63필드 레코드가 정상 파싱되고 원시 63필드를 바이트 동등하게 보존한다 (패스스루)")
        void domesticQuote_parsesWith63FieldRecord_andPreservesAllFields() {
            String record =
                    KisFrameFixtures.syntheticRecord(
                            "005930", KisFrameFixtures.DOMESTIC_QUOTE_FIELD_COUNT);
            Map<String, String> entry =
                    KisFrameFixtures.tickEntry(
                            "005930", "H0STASP0", record, KisFrameFixtures.TRACE_ID);

            List<TickObservation> observations = parser.parse(ConsumedStream.TICK_DOMESTIC, entry);

            assertThat(observations).hasSize(1);
            assertThat(observations.getFirst()).isInstanceOf(QuoteObservation.class);
            QuoteObservation quote = (QuoteObservation) observations.getFirst();
            assertThat(quote.rawRecord()).isEqualTo(record);
            assertThat(KisFrameFixtures.split(quote.rawRecord())).hasSize(63);
        }
    }

    @Nested
    @DisplayName("AC-12 — 엔트리 4필드 계약 + 배치 분할")
    class BatchSplitting {

        @Test
        @DisplayName("① 국내 count=007 배치 프레임에서 체결 관측치를 정확히 7건 얻는다")
        void domesticBatch_yieldsExactlySevenObservations() {
            String data =
                    KisFrameFixtures.batch(
                            KisFrameFixtures.DOMESTIC_TRADE_RECORD, 2, 12, 313_000L, 825L, 7);
            Map<String, String> entry =
                    KisFrameFixtures.tickEntry(
                            "005930", "H0STCNT0", data, KisFrameFixtures.TRACE_ID);

            List<TickObservation> observations = parser.parse(ConsumedStream.TICK_DOMESTIC, entry);

            assertThat(observations).hasSize(7);
        }

        @Test
        @DisplayName("② 7건의 체결가·체결량이 레코드별로 개별 일치한다 (첫 레코드의 7회 반복이 아님)")
        void domesticBatch_preservesPerRecordValues() {
            String data =
                    KisFrameFixtures.batch(
                            KisFrameFixtures.DOMESTIC_TRADE_RECORD, 2, 12, 313_000L, 825L, 7);
            Map<String, String> entry =
                    KisFrameFixtures.tickEntry(
                            "005930", "H0STCNT0", data, KisFrameFixtures.TRACE_ID);

            List<TradeTickObservation> trades =
                    tradesOf(parser.parse(ConsumedStream.TICK_DOMESTIC, entry));

            assertThat(trades)
                    .extracting(t -> t.price().toPlainString())
                    .containsExactly(
                            "313000", "313001", "313002", "313003", "313004", "313005", "313006");
            assertThat(trades)
                    .extracting(TradeTickObservation::volume)
                    .containsExactly(825L, 826L, 827L, 828L, 829L, 830L, 831L);
        }

        @Test
        @DisplayName("③ 배치 전 건이 엔트리의 trace_id를 승계한다")
        void domesticBatch_inheritsTraceId() {
            String data =
                    KisFrameFixtures.batch(
                            KisFrameFixtures.DOMESTIC_TRADE_RECORD, 2, 12, 313_000L, 825L, 7);
            Map<String, String> entry =
                    KisFrameFixtures.tickEntry(
                            "005930", "H0STCNT0", data, KisFrameFixtures.TRACE_ID);

            List<TickObservation> observations = parser.parse(ConsumedStream.TICK_DOMESTIC, entry);

            assertThat(observations)
                    .allSatisfy(o -> assertThat(o.traceId()).isEqualTo(KisFrameFixtures.TRACE_ID));
        }

        @Test
        @DisplayName("④ 해외 count=003 배치 프레임에서 체결 관측치를 정확히 3건 얻는다")
        void overseasBatch_yieldsExactlyThreeObservations() {
            String data =
                    KisFrameFixtures.batch(
                            KisFrameFixtures.OVERSEAS_TRADE_RECORD, 11, 19, 298L, 218L, 3);
            Map<String, String> entry =
                    KisFrameFixtures.tickEntry(
                            "DNASAAPL", "HDFSCNT0", data, KisFrameFixtures.TRACE_ID);

            List<TickObservation> observations = parser.parse(ConsumedStream.TICK_OVERSEAS, entry);

            assertThat(observations).hasSize(3);
        }

        @Test
        @DisplayName("E1 — 후행 빈 필드가 있는 배치도 분할이 정확하다 (split limit -1 강제)")
        void batchWithTrailingEmptyField_splitsCorrectly() {
            // Arrange — [46](2026-09-22 정정 트레일링 필드, 현재 레코드 최후미)를 빈 값으로 만들어 레코드가 빈 필드로 끝나게 한다.
            String recordEndingEmpty =
                    KisFrameFixtures.withField(KisFrameFixtures.DOMESTIC_TRADE_RECORD, 46, "");
            String data = KisFrameFixtures.batch(recordEndingEmpty, 2, 12, 313_000L, 825L, 3);
            Map<String, String> entry =
                    KisFrameFixtures.tickEntry(
                            "005930", "H0STCNT0", data, KisFrameFixtures.TRACE_ID);

            // Act
            List<TickObservation> observations = parser.parse(ConsumedStream.TICK_DOMESTIC, entry);

            // Assert — limit 없는 split이면 마지막 빈 필드가 버려져 140필드가 되고 47로 나누어떨어지지 않아 예외가 난다.
            assertThat(observations).hasSize(3);
        }
    }

    @Nested
    @DisplayName("AC-13 — 호가 패스스루 (필드 미해석)")
    class QuotePassthrough {

        @Test
        @DisplayName("① 국내 호가 63필드 엔트리는 원시 문자열을 바이트 동등하게 싣는다")
        void domesticQuote_carriesRawRecordVerbatim() {
            String record =
                    KisFrameFixtures.syntheticRecord(
                            "005930", KisFrameFixtures.DOMESTIC_QUOTE_FIELD_COUNT);
            Map<String, String> entry =
                    KisFrameFixtures.tickEntry(
                            "005930", "H0STASP0", record, KisFrameFixtures.TRACE_ID);

            List<TickObservation> observations = parser.parse(ConsumedStream.TICK_DOMESTIC, entry);

            assertThat(observations).hasSize(1);
            assertThat(observations.getFirst()).isInstanceOf(QuoteObservation.class);
            assertThat(((QuoteObservation) observations.getFirst()).rawRecord()).isEqualTo(record);
        }

        @Test
        @DisplayName("② 해외 호가 71필드 엔트리는 정규 심볼을 [1]에서 취하고 체결 관측치를 만들지 않는다")
        void overseasQuote_usesCanonicalSymbolAndProducesNoTrade() {
            String record =
                    KisFrameFixtures.syntheticOverseasRecord(
                            "DNASAAPL", "AAPL", KisFrameFixtures.OVERSEAS_QUOTE_FIELD_COUNT);
            Map<String, String> entry =
                    KisFrameFixtures.tickEntry(
                            "DNASAAPL", "HDFSASP0", record, KisFrameFixtures.TRACE_ID);

            List<TickObservation> observations = parser.parse(ConsumedStream.TICK_OVERSEAS, entry);

            assertThat(tradesOf(observations)).isEmpty();
            assertThat(observations).singleElement().isInstanceOf(QuoteObservation.class);
            assertThat(((QuoteObservation) observations.getFirst()).symbol()).isEqualTo("AAPL");
            assertThat(((QuoteObservation) observations.getFirst()).transactionId())
                    .isEqualTo("HDFSASP0");
        }
    }

    @Nested
    @DisplayName("AC-14 시나리오 A — 체결 필드 추출 + 해외 심볼 정규화 + ZDIV 자리수 보존")
    class TradeFieldExtraction {

        @Test
        @DisplayName("② 국내 체결가·체결량·누적거래량·체결시각이 실측값과 일치한다")
        void domesticTrade_extractsMeasuredValues() {
            TradeTickObservation trade = parseSingleDomesticTrade();

            assertThat(trade.price()).isEqualByComparingTo("313000");
            assertThat(trade.volume()).isEqualTo(825L);
            assertThat(trade.accumulatedVolume()).isEqualTo(34_502_265L);
            assertThat(trade.tradeTime()).isEqualTo(LocalTime.of(15, 14, 25));
        }

        @Test
        @DisplayName("① 국내 정규 심볼은 [0] 종목코드이며 원시 tr_key와 동일하다")
        void domesticTrade_usesIndexZeroSymbol() {
            TradeTickObservation trade = parseSingleDomesticTrade();

            assertThat(trade.symbol()).isEqualTo("005930");
            assertThat(trade.rawTrKey()).isEqualTo("005930");
            assertThat(trade.market()).isEqualTo(Market.DOMESTIC);
        }

        @Test
        @DisplayName("① 해외 정규 심볼은 [1] SYMB(`AAPL`)이며 원시 tr_key(`DNASAAPL`)는 따로 보존된다")
        void overseasTrade_normalizesSymbolFromPayload() {
            TradeTickObservation trade = parseSingleOverseasTrade();

            assertThat(trade.symbol()).isEqualTo("AAPL");
            assertThat(trade.rawTrKey()).isEqualTo("DNASAAPL");
        }

        @Test
        @DisplayName("③ 해외 체결가·체결량·누적거래량·체결시각(KHMS 기준)이 실측값과 일치한다")
        void overseasTrade_extractsMeasuredValues() {
            TradeTickObservation trade = parseSingleOverseasTrade();

            assertThat(trade.price()).isEqualByComparingTo("298.4700");
            assertThat(trade.volume()).isEqualTo(218L);
            assertThat(trade.accumulatedVolume()).isEqualTo(8_998_906L);
            // KHMS(003004, KST) 기준 — XHMS(113004, ET)가 아니다.
            assertThat(trade.tradeTime()).isEqualTo(LocalTime.of(0, 30, 4));
        }

        @Test
        @DisplayName("④ 해외 정규 심볼이 동일 종목의 신호 엔트리 symbol과 문자열 동등하다 (상관 불변식)")
        void overseasTradeSymbol_matchesSignalSymbol() {
            TradeTickObservation trade = parseSingleOverseasTrade();

            assertThat(trade.symbol()).isEqualTo(KisFrameFixtures.signalEntry().get("symbol"));
        }

        @Test
        @DisplayName("⑥ 해외 관측치의 ZDIV는 정수 4로 보존되고 국내 관측치의 ZDIV는 null이다")
        void priceScale_preservedForOverseas_nullForDomestic() {
            assertThat(parseSingleOverseasTrade().priceScale()).isEqualTo(4);
            assertThat(parseSingleDomesticTrade().priceScale()).isNull();
        }

        @Test
        @DisplayName("⑦ 해외 체결가의 scale()이 정확히 ZDIV(4)와 일치한다 (stripTrailingZeros 미적용 증거)")
        void overseasPrice_preservesExactScale() {
            TradeTickObservation trade = parseSingleOverseasTrade();

            assertThat(trade.price().scale()).isEqualTo(4);
            assertThat(trade.price()).isEqualTo(new BigDecimal("298.4700"));
            assertThat(trade.price().toPlainString()).isEqualTo("298.4700");
        }

        @Test
        @DisplayName("⑨ 음성 대조 — 체결가가 0.02984700도 2984700.0도 아니다 (이중 스케일링 부재 증거)")
        void overseasPrice_isNotDoubleScaled() {
            TradeTickObservation trade = parseSingleOverseasTrade();

            assertThat(trade.price()).isNotEqualByComparingTo(new BigDecimal("0.02984700"));
            assertThat(trade.price()).isNotEqualByComparingTo(new BigDecimal("2984700.0"));
            assertThat(trade.price()).isEqualByComparingTo(new BigDecimal("298.4700"));
        }
    }

    @Nested
    @DisplayName("AC-10 / E2 / E3 — 해석 불가 판정")
    class Unparseable {

        @Test
        @DisplayName("미지의 trId는 해석 불가다")
        void unknownTransactionId_isUnparseable() {
            Map<String, String> entry =
                    KisFrameFixtures.tickEntry(
                            "005930", "H0STXXX0", "a^b^c", KisFrameFixtures.TRACE_ID);

            assertThatThrownBy(() -> parser.parse(ConsumedStream.TICK_DOMESTIC, entry))
                    .isInstanceOf(PayloadUnparseableException.class)
                    .hasMessageContaining("H0STXXX0");
        }

        @Test
        @DisplayName("레코드 필드 수로 나누어떨어지지 않는 data는 해석 불가다")
        void nonDivisibleFieldCount_isUnparseable() {
            String data = KisFrameFixtures.DOMESTIC_TRADE_RECORD + "^extra";
            Map<String, String> entry =
                    KisFrameFixtures.tickEntry(
                            "005930", "H0STCNT0", data, KisFrameFixtures.TRACE_ID);

            assertThatThrownBy(() -> parser.parse(ConsumedStream.TICK_DOMESTIC, entry))
                    .isInstanceOf(PayloadUnparseableException.class);
        }

        @Test
        @DisplayName("E2 — data가 빈 문자열이면 해석 불가다")
        void emptyData_isUnparseable() {
            Map<String, String> entry =
                    KisFrameFixtures.tickEntry("005930", "H0STCNT0", "", KisFrameFixtures.TRACE_ID);

            assertThatThrownBy(() -> parser.parse(ConsumedStream.TICK_DOMESTIC, entry))
                    .isInstanceOf(PayloadUnparseableException.class);
        }

        @Test
        @DisplayName("필수 엔트리 필드(trId)가 없으면 해석 불가다")
        void missingRequiredEntryField_isUnparseable() {
            Map<String, String> entry =
                    KisFrameFixtures.without(
                            KisFrameFixtures.tickEntry(
                                    "005930", "H0STCNT0", "x", KisFrameFixtures.TRACE_ID),
                            "trId");

            assertThatThrownBy(() -> parser.parse(ConsumedStream.TICK_DOMESTIC, entry))
                    .isInstanceOf(PayloadUnparseableException.class)
                    .hasMessageContaining("trId");
        }

        @Test
        @DisplayName("E3 — 체결가가 숫자가 아니면 해석 불가다 (예외 삼킴 금지)")
        void nonNumericPrice_isUnparseable() {
            String data =
                    KisFrameFixtures.withField(KisFrameFixtures.DOMESTIC_TRADE_RECORD, 2, "N/A");
            Map<String, String> entry =
                    KisFrameFixtures.tickEntry(
                            "005930", "H0STCNT0", data, KisFrameFixtures.TRACE_ID);

            assertThatThrownBy(() -> parser.parse(ConsumedStream.TICK_DOMESTIC, entry))
                    .isInstanceOf(PayloadUnparseableException.class);
        }

        @Test
        @DisplayName("E3 — 체결량이 숫자가 아니면 해석 불가다")
        void nonNumericVolume_isUnparseable() {
            String data =
                    KisFrameFixtures.withField(KisFrameFixtures.DOMESTIC_TRADE_RECORD, 12, "많음");
            Map<String, String> entry =
                    KisFrameFixtures.tickEntry(
                            "005930", "H0STCNT0", data, KisFrameFixtures.TRACE_ID);

            assertThatThrownBy(() -> parser.parse(ConsumedStream.TICK_DOMESTIC, entry))
                    .isInstanceOf(PayloadUnparseableException.class);
        }

        @Test
        @DisplayName("체결 시각이 HHMMSS가 아니면 해석 불가다")
        void malformedTradeTime_isUnparseable() {
            String data =
                    KisFrameFixtures.withField(KisFrameFixtures.DOMESTIC_TRADE_RECORD, 1, "99:99");
            Map<String, String> entry =
                    KisFrameFixtures.tickEntry(
                            "005930", "H0STCNT0", data, KisFrameFixtures.TRACE_ID);

            assertThatThrownBy(() -> parser.parse(ConsumedStream.TICK_DOMESTIC, entry))
                    .isInstanceOf(PayloadUnparseableException.class);
        }

        @Test
        @DisplayName("스트림 시장과 trId 시장이 어긋나면 해석 불가다 (스트림이 상위 권위)")
        void marketMismatchBetweenStreamAndTrId_isUnparseable() {
            Map<String, String> entry =
                    KisFrameFixtures.tickEntry(
                            "DNASAAPL",
                            "HDFSCNT0",
                            KisFrameFixtures.OVERSEAS_TRADE_RECORD,
                            KisFrameFixtures.TRACE_ID);

            assertThatThrownBy(() -> parser.parse(ConsumedStream.TICK_DOMESTIC, entry))
                    .isInstanceOf(PayloadUnparseableException.class);
        }
    }

    private TradeTickObservation parseSingleDomesticTrade() {
        Map<String, String> entry =
                KisFrameFixtures.tickEntry(
                        "005930",
                        "H0STCNT0",
                        KisFrameFixtures.DOMESTIC_TRADE_RECORD,
                        KisFrameFixtures.TRACE_ID);
        return tradesOf(parser.parse(ConsumedStream.TICK_DOMESTIC, entry)).getFirst();
    }

    private TradeTickObservation parseSingleOverseasTrade() {
        Map<String, String> entry =
                KisFrameFixtures.tickEntry(
                        "DNASAAPL",
                        "HDFSCNT0",
                        KisFrameFixtures.OVERSEAS_TRADE_RECORD,
                        KisFrameFixtures.TRACE_ID);
        return tradesOf(parser.parse(ConsumedStream.TICK_OVERSEAS, entry)).getFirst();
    }

    private static List<TradeTickObservation> tradesOf(List<TickObservation> observations) {
        return observations.stream()
                .filter(TradeTickObservation.class::isInstance)
                .map(TradeTickObservation.class::cast)
                .toList();
    }
}
