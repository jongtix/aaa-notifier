package com.aaa.notifier.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 관측치 타입 모양 계약 테스트 (plan.md §B, REQ-NOTIFIER-CONSUMER-033/041/051).
 *
 * <p>하류(FILTER-001)가 그대로 소비할 타입이므로 모양 자체를 테스트로 고정한다. 특히 체결가가 {@code BigDecimal}이고 자리수(scale)를 보존한다는
 * 점, 그리고 {@code ZDIV}(해외만, 국내 {@code null})를 체결가와 **함께** 싣는다는 점이 REQ-051 후단의 하류 재검출 전제다.
 */
@DisplayName("관측치 타입 모양 계약 (plan.md §B)")
class ObservationContractTest {

    private static final String TRACE_ID = "0b4a5f2e-1c3d-4e5f-8a9b-0c1d2e3f4a5b";

    @Nested
    @DisplayName("체결 관측치 (§B.1)")
    class TradeTick {

        @Test
        @DisplayName("해외 체결은 정규 심볼·원시 tr_key를 함께 싣고 ZDIV를 보존한다")
        void overseasTrade_carriesCanonicalSymbolRawTrKeyAndPriceScale() {
            TradeTickObservation observation =
                    new TradeTickObservation(
                            "AAPL",
                            "DNASAAPL",
                            Market.OVERSEAS,
                            new BigDecimal("298.4700"),
                            218L,
                            8_998_906L,
                            LocalTime.of(0, 30, 4),
                            4,
                            TRACE_ID);

            assertThat(observation.symbol()).isEqualTo("AAPL");
            assertThat(observation.rawTrKey()).isEqualTo("DNASAAPL");
            assertThat(observation.priceScale()).isEqualTo(4);
            assertThat(observation.price().scale()).isEqualTo(4);
        }

        @Test
        @DisplayName("국내 체결의 ZDIV는 null이다 (필드 자체가 존재하지 않음)")
        void domesticTrade_hasNullPriceScale() {
            TradeTickObservation observation =
                    new TradeTickObservation(
                            "005930",
                            "005930",
                            Market.DOMESTIC,
                            new BigDecimal("313000"),
                            825L,
                            34_502_265L,
                            LocalTime.of(15, 14, 25),
                            null,
                            TRACE_ID);

            assertThat(observation.priceScale()).isNull();
            assertThat(observation.market()).isEqualTo(Market.DOMESTIC);
        }

        @Test
        @DisplayName("체결가 null은 생성 시점에 거부된다")
        void nullPrice_isRejected() {
            assertThatThrownBy(
                            () ->
                                    new TradeTickObservation(
                                            "005930",
                                            "005930",
                                            Market.DOMESTIC,
                                            null,
                                            1L,
                                            1L,
                                            LocalTime.NOON,
                                            null,
                                            TRACE_ID))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("호가 패스스루 관측치 (§B.2)")
    class QuotePassthrough {

        @Test
        @DisplayName("원시 레코드 문자열을 그대로 싣는다 (필드 해석 없음)")
        void quote_carriesRawRecordVerbatim() {
            QuoteObservation observation =
                    new QuoteObservation(
                            "005930",
                            "005930",
                            Market.DOMESTIC,
                            "H0STASP0",
                            "005930^151425^0^313500",
                            TRACE_ID);

            assertThat(observation.rawRecord()).isEqualTo("005930^151425^0^313500");
            assertThat(observation.transactionId()).isEqualTo("H0STASP0");
        }
    }

    @Nested
    @DisplayName("신호 관측치 (§B.3)")
    class Signal {

        @Test
        @DisplayName("horizon은 문자열 그대로 보존된다 (enum 변환 금지 — 밴드 조회 키 무변환 사용)")
        void signal_preservesHorizonVerbatim() {
            SignalObservation observation =
                    new SignalObservation(
                            "AAPL",
                            "D20",
                            LocalDate.of(2026, 9, 18),
                            TRACE_ID,
                            "STRONG_BUY",
                            new BigDecimal("0.043"),
                            new BigDecimal("0.71"));

            assertThat(observation.horizon()).isEqualTo("D20");
            assertThat(observation.score()).isEqualByComparingTo("0.043");
            assertThat(observation.confidence()).isEqualByComparingTo("0.71");
            assertThat(observation.tradeDate()).isEqualTo(LocalDate.of(2026, 9, 18));
        }
    }

    @Nested
    @DisplayName("틱 관측치 봉인 계층 (sealed)")
    class SealedHierarchy {

        @Test
        @DisplayName("체결·호가 관측치는 TickObservation의 유일한 두 구현이다")
        void tickObservation_permitsExactlyTradeAndQuote() {
            assertThat(TickObservation.class.getPermittedSubclasses())
                    .containsExactlyInAnyOrder(TradeTickObservation.class, QuoteObservation.class);
        }
    }
}
