package com.aaa.notifier.stream;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 해외 체결가 {@code ZDIV} 자리수 검증 WARN 폴백 테스트 (REQ-NOTIFIER-CONSUMER-034, AC-14 시나리오 B, 엣지케이스 E4).
 *
 * <p>시나리오 A(정상 프레임)와 Given이 다르므로 acceptance.md AC-14 서두 규정에 따라 테스트 클래스를 분리한다.
 *
 * <p>핵심 경계: 3조건 중 무엇에 걸려도 <b>폐기하지 않고 원문 그대로 전달</b>하며, 값을 추측 보정하지 않고, {@code ZDIV}를 지우지도 않는다 — 그 둘이
 * 하류의 유일한 재검출 수단이기 때문이다(REQ-051 후단). DLQ 이관 부재(단언 ⑪)와 확인응답(단언 ⑫)은 소비 루프 계약이므로 {@code
 * StreamConsumerWorkerTest}·통합 테스트가 담당한다.
 */
@DisplayName("TickPayloadParser — ZDIV 자리수 검증 WARN 폴백 3조건 (REQ-034, AC-14B)")
class TickPayloadParserZdivFallbackTest {

    private static final int ZDIV_INDEX = 2;
    private static final int LAST_INDEX = 11;

    private final TickPayloadParser parser = new TickPayloadParser();
    private ListAppender<ILoggingEvent> logAppender;
    private Logger parserLogger;

    @BeforeEach
    void attachLogAppender() {
        parserLogger = (Logger) LoggerFactory.getLogger(TickPayloadParser.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        parserLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        parserLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Nested
    @DisplayName("조건 ① — ZDIV가 정수로 판독되지 않는 경우")
    class NonIntegerZdiv {

        @Test
        @DisplayName("⑩ 폐기하지 않고 체결 관측치를 전달하며 ZDIV는 null이 된다")
        void nonIntegerZdiv_stillDelivers() {
            TradeTickObservation trade = parseOverseasWith(ZDIV_INDEX, "X");

            assertThat(trade.price()).isEqualByComparingTo("298.4700");
            assertThat(trade.priceScale()).isNull();
        }

        @Test
        @DisplayName("⑬ WARN 로그 1건에 trace_id·원시 tr_key·ZDIV 원값·체결가 원문이 모두 포함된다")
        void nonIntegerZdiv_logsWarnWithDiagnostics() {
            parseOverseasWith(ZDIV_INDEX, "X");

            assertThat(warnMessages()).singleElement().satisfies(this::assertDiagnosticsPresent);
        }

        private void assertDiagnosticsPresent(String message) {
            assertThat(message)
                    .contains(KisFrameFixtures.TRACE_ID)
                    .contains("DNASAAPL")
                    .contains("X")
                    .contains("298.4700");
        }
    }

    @Nested
    @DisplayName("조건 ② — ZDIV가 기대 범위 0~4를 벗어나는 경우")
    class OutOfRangeZdiv {

        @Test
        @DisplayName("⑩ 폐기하지 않고 전달하며 판독된 ZDIV 원값(7)을 그대로 보존한다")
        void outOfRangeZdiv_stillDeliversAndPreservesValue() {
            TradeTickObservation trade = parseOverseasWith(ZDIV_INDEX, "7");

            assertThat(trade.price()).isEqualByComparingTo("298.4700");
            assertThat(trade.priceScale()).isEqualTo(7);
        }

        @Test
        @DisplayName("⑬ WARN 로그가 1건 남는다")
        void outOfRangeZdiv_logsSingleWarn() {
            parseOverseasWith(ZDIV_INDEX, "7");

            assertThat(warnMessages()).hasSize(1);
            assertThat(warnMessages().getFirst()).contains("7");
        }

        @Test
        @DisplayName("경계값 0과 4는 범위 내이므로 WARN이 없다")
        void boundaryZdivValues_produceNoWarn() {
            // Arrange — ZDIV=0 + 자리수 0인 체결가, ZDIV=4 + 자리수 4인 체결가.
            String zeroScale =
                    KisFrameFixtures.withField(
                            KisFrameFixtures.withField(
                                    KisFrameFixtures.OVERSEAS_TRADE_RECORD, ZDIV_INDEX, "0"),
                            LAST_INDEX,
                            "298");

            // Act
            parseOverseasRecord(zeroScale);
            parseOverseasRecord(KisFrameFixtures.OVERSEAS_TRADE_RECORD);

            // Assert
            assertThat(warnMessages()).isEmpty();
        }
    }

    @Nested
    @DisplayName("조건 ③ — 체결가 소수 자리수가 ZDIV와 불일치하는 경우 (비스케일 정수 도착)")
    class ScaleMismatch {

        @Test
        @DisplayName("⑭ 체결가가 원문 2984700 그대로이며 298.4700으로 보정되지 않는다")
        void scaleMismatch_leavesPriceUncorrected() {
            TradeTickObservation trade = parseOverseasWith(LAST_INDEX, "2984700");

            assertThat(trade.price().toPlainString()).isEqualTo("2984700");
            assertThat(trade.price()).isNotEqualByComparingTo("298.4700");
        }

        @Test
        @DisplayName("⑮ ZDIV 정수 4가 그대로 보존되어 하류가 scale(0) != ZDIV(4)로 재검출할 수 있다")
        void scaleMismatch_preservesPriceScaleForDownstreamRedetection() {
            TradeTickObservation trade = parseOverseasWith(LAST_INDEX, "2984700");

            assertThat(trade.priceScale()).isEqualTo(4);
            assertThat(trade.price().scale()).isZero();
            assertThat(trade.price().scale()).isNotEqualTo(trade.priceScale());
        }

        @Test
        @DisplayName("⑬ WARN 로그 1건에 진단 항목이 모두 포함된다")
        void scaleMismatch_logsWarnWithDiagnostics() {
            parseOverseasWith(LAST_INDEX, "2984700");

            assertThat(warnMessages())
                    .singleElement()
                    .satisfies(
                            message ->
                                    assertThat(message)
                                            .contains(KisFrameFixtures.TRACE_ID)
                                            .contains("DNASAAPL")
                                            .contains("4")
                                            .contains("2984700"));
        }
    }

    @Nested
    @DisplayName("단언 ⑧ — 배율 연산 정적 부재")
    class NoScalingOperations {

        @Test
        @DisplayName("스트림 패키지 소스에 ZDIV를 지수로 쓰는 배율 연산이 0건이다")
        void streamPackage_containsNoPowerOfTenScaling() {
            List<String> violations =
                    StreamSourceScan.findOccurrences(
                            List.of(
                                    "movePointLeft",
                                    "movePointRight",
                                    "scaleByPowerOfTen",
                                    "Math.pow(10",
                                    "TEN.pow("));

            assertThat(violations)
                    .as("배율 연산은 이미 스케일된 값을 이중 스케일링해 오염시킨다 (REQ-034, plan.md §G)")
                    .isEmpty();
        }

        @Test
        @DisplayName("스트림 패키지 소스에 stripTrailingZeros 호출이 0건이다")
        void streamPackage_containsNoStripTrailingZeros() {
            List<String> violations =
                    StreamSourceScan.findOccurrences(List.of("stripTrailingZeros"));

            assertThat(violations)
                    .as("stripTrailingZeros는 298.4700의 scale을 2로 줄여 ZDIV 자리수 검증을 무력화한다")
                    .isEmpty();
        }
    }

    private TradeTickObservation parseOverseasWith(int index, String value) {
        return parseOverseasRecord(
                KisFrameFixtures.withField(KisFrameFixtures.OVERSEAS_TRADE_RECORD, index, value));
    }

    private TradeTickObservation parseOverseasRecord(String record) {
        Map<String, String> entry =
                KisFrameFixtures.tickEntry(
                        "DNASAAPL", "HDFSCNT0", record, KisFrameFixtures.TRACE_ID);
        return (TradeTickObservation) parser.parse(ConsumedStream.TICK_OVERSEAS, entry).getFirst();
    }

    private List<String> warnMessages() {
        return logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
