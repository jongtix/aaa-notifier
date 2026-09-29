package com.aaa.notifier.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.notifier.stream.Market;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

@DisplayName("ReferenceDataRefresher — 장전 적재·유효 등급 초기값·재시작 승계 (REQ-001/003, E1)")
class ReferenceDataRefresherTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 2026-09-29(화) 08:30 KST — 국내 장전, 해외(ET 09-28 19:30)는 이미 마감. */
    private static final Clock DOMESTIC_PRE_OPEN =
            Clock.fixed(ZonedDateTime.of(2026, 9, 29, 8, 30, 0, 0, KST).toInstant(), KST);

    private ReferenceDataLoader loader;
    private ReferenceDataHolder holder;
    private FilterStateStore store;
    private SimpleMeterRegistry registry;
    private ReferenceDataRefresher refresher;

    @BeforeEach
    void setUp() {
        loader = mock(ReferenceDataLoader.class);
        holder = new ReferenceDataHolder();
        store = mock(FilterStateStore.class);
        registry = new SimpleMeterRegistry();
        refresher =
                new ReferenceDataRefresher(
                        loader, holder, store, FilterMetrics.create(registry), DOMESTIC_PRE_OPEN);
    }

    private static StockReference reference(String symbol, Market market) {
        BandPartition promote =
                new BandPartition(
                        List.of(
                                new PriceBand(
                                        new BigDecimal("29000"),
                                        new BigDecimal("30490"),
                                        Grade.BUY),
                                new PriceBand(
                                        new BigDecimal("30500"),
                                        new BigDecimal("31000"),
                                        Grade.STRONG_BUY)));
        BandPartition demote =
                new BandPartition(
                        List.of(
                                new PriceBand(
                                        new BigDecimal("29000"),
                                        new BigDecimal("29790"),
                                        Grade.BUY),
                                new PriceBand(
                                        new BigDecimal("29800"),
                                        new BigDecimal("31000"),
                                        Grade.STRONG_BUY)));
        return new StockReference(
                1L,
                symbol,
                market,
                LocalDate.of(2026, 9, 28),
                new BigDecimal("30000"),
                new BigDecimal("1000"),
                new BigDecimal("200"),
                Map.of("D20", new HorizonBands(promote, demote)));
    }

    @Test
    @DisplayName("AC-1 ③ — 미존재 유효 등급을 전일 종가가 속한 DEMOTE 파티션 등급으로 장 마감까지 초기화한다")
    void refresh_initializesGradeFromDemotePartitionUntilClose() {
        // Arrange
        when(loader.load(anyMap()))
                .thenReturn(new ReferenceSnapshot(List.of(reference("005930", Market.DOMESTIC))));

        // Act
        refresher.refresh(null);

        // Assert — 전일 종가 30,000은 DEMOTE에서 STRONG_BUY(PROMOTE는 BUY), 08:30 → 15:30 = 7시간
        verify(store).initGradeIfAbsent("005930", "D20", Grade.STRONG_BUY, Duration.ofHours(7));
        assertThat(holder.current().find(Market.DOMESTIC, "005930")).isPresent();
    }

    @Test
    @DisplayName("이미 마감한 시장의 종목은 유효 등급을 초기화하지 않는다")
    void refresh_skipsGradeInitAfterMarketClose() {
        // Arrange
        when(loader.load(anyMap()))
                .thenReturn(new ReferenceSnapshot(List.of(reference("AAPL", Market.OVERSEAS))));

        // Act
        refresher.refresh(null);

        // Assert
        verify(store, never()).initGradeIfAbsent(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("시장별 '당일'을 시장 시간대로 계산해 적재기에 넘긴다")
    void refresh_passesMarketLocalToday() {
        // Arrange
        when(loader.load(anyMap())).thenReturn(ReferenceSnapshot.empty());

        // Act
        refresher.refresh(null);

        // Assert
        verify(loader)
                .load(
                        Map.of(
                                Market.DOMESTIC,
                                LocalDate.of(2026, 9, 29),
                                Market.OVERSEAS,
                                LocalDate.of(2026, 9, 28)));
    }

    @Test
    @DisplayName("E1 — 적재 실패 시 이전 적재분을 유지하고 실패 카운터를 올린다")
    void refresh_keepsPreviousSnapshotOnFailure() {
        // Arrange
        ReferenceSnapshot previous =
                new ReferenceSnapshot(List.of(reference("005930", Market.DOMESTIC)));
        holder.replace(previous);
        when(loader.load(anyMap())).thenThrow(new DataAccessResourceFailureException("down"));

        // Act
        refresher.refresh(Market.DOMESTIC);

        // Assert
        assertThat(holder.current()).isSameAs(previous);
        assertThat(
                        registry.get("notifier.filter.reference.load")
                                .tag("outcome", "failure")
                                .counter()
                                .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("적재 성공 시 적재 종목 수 게이지를 갱신한다")
    void refresh_updatesLoadedStocksGauge() {
        // Arrange
        when(loader.load(anyMap()))
                .thenReturn(new ReferenceSnapshot(List.of(reference("005930", Market.DOMESTIC))));

        // Act
        refresher.refresh(null);

        // Assert
        assertThat(registry.get("notifier.filter.reference.stocks").gauge().value()).isEqualTo(1.0);
    }
}
