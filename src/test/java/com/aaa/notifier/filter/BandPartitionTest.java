package com.aaa.notifier.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("BandPartition — 가격 파티션 조회 (REQ-011/014)")
class BandPartitionTest {

    /** analyzer 스윕 형태: 각 밴드는 그리드 포인트 구간, 인접 밴드 사이에 한 스텝 간격이 있다. */
    private static final BandPartition PARTITION =
            new BandPartition(
                    List.of(
                            new PriceBand(bd("29000"), bd("29790"), Grade.BUY),
                            new PriceBand(bd("29800"), bd("30490"), Grade.STRONG_BUY),
                            new PriceBand(bd("30500"), bd("31000"), Grade.STRONG_BUY)));

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @Nested
    @DisplayName("구간 안 가격")
    class InsideGrid {

        @Test
        @DisplayName("밴드 안의 가격은 그 밴드 등급이며 클램프되지 않는다")
        void priceInsideBand_returnsBandGrade() {
            BandLookup lookup = PARTITION.lookup(bd("29500"));

            assertThat(lookup.grade()).isEqualTo(Grade.BUY);
            assertThat(lookup.clamped()).isFalse();
        }

        @Test
        @DisplayName("밴드 경계값(하한)은 그 밴드에 속한다")
        void lowerBoundary_belongsToBand() {
            assertThat(PARTITION.lookup(bd("29800")).grade()).isEqualTo(Grade.STRONG_BUY);
        }

        @Test
        @DisplayName("인접 밴드 사이 간격의 가격은 아래 밴드(하한 이하 최대 밴드)에 속한다")
        void gapBetweenBands_belongsToLowerBand() {
            assertThat(PARTITION.lookup(bd("29795")).grade()).isEqualTo(Grade.BUY);
        }
    }

    @Nested
    @DisplayName("그리드 밖 가격 — 최외곽 밴드 클램프 (REQ-014)")
    class OutsideGrid {

        @Test
        @DisplayName("최저 하한 미만이면 첫 밴드로 클램프한다")
        void belowGrid_clampsToFirstBand() {
            BandLookup lookup = PARTITION.lookup(bd("20000"));

            assertThat(lookup.grade()).isEqualTo(Grade.BUY);
            assertThat(lookup.clamped()).isTrue();
        }

        @Test
        @DisplayName("최고 상한 초과면 마지막 밴드로 클램프한다")
        void aboveGrid_clampsToLastBand() {
            BandLookup lookup = PARTITION.lookup(bd("40000"));

            assertThat(lookup.grade()).isEqualTo(Grade.STRONG_BUY);
            assertThat(lookup.clamped()).isTrue();
        }

        @Test
        @DisplayName("최고 상한과 같은 가격은 클램프가 아니다")
        void exactUpperBound_isNotClamped() {
            assertThat(PARTITION.lookup(bd("31000")).clamped()).isFalse();
        }
    }

    @Test
    @DisplayName("빈 파티션은 생성 시점에 거부한다")
    void emptyPartition_isRejected() {
        List<PriceBand> empty = List.of();

        assertThatThrownBy(() -> new BandPartition(empty))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
