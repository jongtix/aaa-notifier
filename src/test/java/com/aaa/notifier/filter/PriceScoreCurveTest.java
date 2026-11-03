package com.aaa.notifier.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PriceScoreCurve — 가격-score 보간·클램프 (SPEC-SIGNAL-PRICECURVE-001 REQ-008/009)")
class PriceScoreCurveTest {

    /** 29000~31000, 0.5% 그리드 폭 — BandPartitionTest와 같은 가격대. */
    private static final PriceScoreCurve CURVE =
            new PriceScoreCurve(
                    List.of(
                            new PricePoint(bd("29000"), bd("0.0200")),
                            new PricePoint(bd("30000"), bd("0.0400")),
                            new PricePoint(bd("31000"), bd("0.0100"))));

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @Nested
    @DisplayName("그리드 안 보간 (REQ-008)")
    class Interpolation {

        @Test
        @DisplayName("두 그리드 포인트 사이 중간값은 선형 보간이다")
        void midpoint_interpolatesLinearly() {
            // 29000→0.02, 30000→0.04 — 중간 29500은 0.03
            assertThat(CURVE.scoreAt(bd("29500"))).isEqualByComparingTo("0.03");
        }

        @Test
        @DisplayName("임의 비율 지점도 공식 S1+(S2-S1)*(P-P1)/(P2-P1)과 일치한다")
        void arbitraryRatio_matchesLinearFormula() {
            // 30000→0.04, 31000→0.01 — 30250(비율 0.25)은 0.04+(0.01-0.04)*0.25 = 0.0325
            assertThat(CURVE.scoreAt(bd("30250"))).isEqualByComparingTo("0.0325");
        }

        @Test
        @DisplayName("E1 — 그리드 포인트와 정확히 일치하는 가격은 그 포인트의 score를 그대로 반환한다(0으로 나누기 없음)")
        void exactGridPoint_returnsExactScore() {
            assertThat(CURVE.scoreAt(bd("30000"))).isEqualByComparingTo("0.04");
        }
    }

    @Nested
    @DisplayName("범위 밖 클램프, 외삽 없음 (REQ-009)")
    class Clamp {

        @Test
        @DisplayName("최솟값보다 작으면 첫 포인트의 score로 클램프한다")
        void belowRange_clampsToFirstPoint() {
            assertThat(CURVE.scoreAt(bd("20000"))).isEqualByComparingTo("0.02");
        }

        @Test
        @DisplayName("최댓값보다 크면 마지막 포인트의 score로 클램프한다")
        void aboveRange_clampsToLastPoint() {
            assertThat(CURVE.scoreAt(bd("40000"))).isEqualByComparingTo("0.01");
        }

        @Test
        @DisplayName("최솟값과 정확히 같으면 클램프 경로로도 첫 포인트 score와 같다")
        void atLowerBound_returnsFirstPointScore() {
            assertThat(CURVE.scoreAt(bd("29000"))).isEqualByComparingTo("0.02");
        }

        @Test
        @DisplayName("최댓값과 정확히 같으면 클램프 경로로도 마지막 포인트 score와 같다")
        void atUpperBound_returnsLastPointScore() {
            assertThat(CURVE.scoreAt(bd("31000"))).isEqualByComparingTo("0.01");
        }
    }

    @Test
    @DisplayName("단일 포인트 커브는 어떤 가격에서도 그 포인트의 score를 반환한다(0으로 나누기 없음)")
    void singlePointCurve_alwaysReturnsThatScore() {
        PriceScoreCurve single =
                new PriceScoreCurve(List.of(new PricePoint(bd("30000"), bd("0.05"))));

        assertThat(single.scoreAt(bd("10000"))).isEqualByComparingTo("0.05");
        assertThat(single.scoreAt(bd("30000"))).isEqualByComparingTo("0.05");
        assertThat(single.scoreAt(bd("99999"))).isEqualByComparingTo("0.05");
    }

    @Test
    @DisplayName("빈 포인트 목록은 생성 시점에 거부한다")
    void emptyPoints_isRejected() {
        List<PricePoint> empty = List.of();

        assertThatThrownBy(() -> new PriceScoreCurve(empty))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
