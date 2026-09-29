package com.aaa.notifier.filter;

import static com.aaa.notifier.filter.FilterTestFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.notifier.stream.Market;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("IntradayTracker — 당일 고저 누적과 재전달 멱등 (REQ-022, E2)")
class IntradayTrackerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 29);

    private final IntradayTracker tracker = new IntradayTracker();

    @Test
    @DisplayName("당일 체결가로 고가·저가를 누적해 변동폭을 돌려준다")
    void accumulatesRange() {
        tracker.record(Market.DOMESTIC, "005930", DAY, bd("30000"), 10);
        tracker.record(Market.DOMESTIC, "005930", DAY, bd("30500"), 20);
        Optional<IntradayTracker.Snapshot> last =
                tracker.record(Market.DOMESTIC, "005930", DAY, bd("29800"), 30);

        assertThat(last).isPresent();
        assertThat(last.get().range()).isEqualByComparingTo("700");
    }

    @Test
    @DisplayName("누적 거래량이 늘지 않은 관측치는 재전달로 보고 건너뛴다 (엔트리 단위 재전달 멱등)")
    void nonIncreasingAccumulatedVolume_isDuplicate() {
        tracker.record(Market.DOMESTIC, "005930", DAY, bd("30000"), 10);
        tracker.record(Market.DOMESTIC, "005930", DAY, bd("30100"), 20);

        assertThat(tracker.record(Market.DOMESTIC, "005930", DAY, bd("30100"), 20)).isEmpty();
        assertThat(tracker.record(Market.DOMESTIC, "005930", DAY, bd("30000"), 10)).isEmpty();
    }

    @Test
    @DisplayName("거래일이 바뀌면 고저·누적 거래량을 새로 시작한다")
    void newTradingDay_resetsState() {
        tracker.record(Market.DOMESTIC, "005930", DAY, bd("30000"), 1_000);

        Optional<IntradayTracker.Snapshot> next =
                tracker.record(Market.DOMESTIC, "005930", DAY.plusDays(1), bd("31000"), 5);

        assertThat(next).isPresent();
        assertThat(next.get().range()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("누적 거래량이 0으로만 오는 시장은 중복 판정을 하지 않는다")
    void zeroAccumulatedVolume_isNeverDeduplicated() {
        tracker.record(Market.OVERSEAS, "AAPL", DAY, bd("100"), 0);

        assertThat(tracker.record(Market.OVERSEAS, "AAPL", DAY, bd("101"), 0)).isPresent();
    }
}
