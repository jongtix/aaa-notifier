package com.aaa.notifier.filter;

import com.aaa.notifier.stream.Market;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 장전 cron + 기동 시 참조 데이터 재구성 (REQ-NOTIFIER-FILTER-001/003).
 *
 * <p><b>장전 cron</b>은 시장별로 개장 전에 1회 발화한다(ADR-008 — cron 전용). <b>기동</b> 시에도 같은 절차로 1회 재구성한다 — 장중 재시작
 * 후 참조 데이터 공백을 없애기 위해서다. 두 경로 모두 Redis의 유효 등급({@code filter:grade})은 <b>없을 때만</b> 초기화한다 — 장중 재시작이
 * 기존 등급을 덮어쓰면 재시작 직후 전환이 오탐/누락된다(REQ-003 후단).
 *
 * <p><b>적재 실패</b>(DB 장애)는 이전 스냅샷 유지 + WARN + 실패 카운터로 처리한다(plan.md §H R3, acceptance E1) — 예외를 전파해
 * 스케줄러 스레드를 오염시키지 않는다.
 */
@Slf4j
@RequiredArgsConstructor
public class ReferenceDataRefresher {

    private final ReferenceDataLoader loader;
    private final ReferenceDataHolder holder;
    private final FilterStateStore stateStore;
    private final FilterMetrics metrics;
    private final Clock clock;

    /** 국내 장전 적재 (KST). */
    @Scheduled(cron = "${notifier.filter.reference-load.domestic-cron}", zone = "Asia/Seoul")
    public void onDomesticPreOpen() {
        refresh(Market.DOMESTIC);
    }

    /** 해외 장전 적재 (ET — 서머타임 자동 반영). */
    @Scheduled(cron = "${notifier.filter.reference-load.overseas-cron}", zone = "America/New_York")
    public void onOverseasPreOpen() {
        refresh(Market.OVERSEAS);
    }

    /** 기동(장중 재시작 포함) 시 1회 재구성 — 개장 리셋은 하지 않는다(REQ-003). */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh(null);
    }

    /**
     * 참조 데이터를 재적재하고 유효 등급 초기값을 세팅한다.
     *
     * @param openingMarket 곧 개장하는 시장(장전 cron). 기동 경로는 {@code null}
     */
    public void refresh(Market openingMarket) {
        ReferenceSnapshot snapshot;
        try {
            snapshot = loader.load(marketToday());
        } catch (DataAccessException e) {
            metrics.referenceLoad(false, 0);
            log.warn(
                    "[filter-reference] 참조 데이터 적재 실패 — 이전 적재분 유지 opening={} retained={}",
                    openingMarket,
                    holder.current().stocks().size(),
                    e);
            return;
        }
        holder.replace(snapshot);
        metrics.referenceLoad(true, snapshot.stocks().size());
        int initialized = initializeGrades(snapshot);
        log.info(
                "[filter-reference] 참조 데이터 적재 완료 opening={} stocks={} gradesInitialized={}",
                openingMarket,
                snapshot.stocks().size(),
                initialized);
    }

    private Map<Market, LocalDate> marketToday() {
        return Map.of(
                Market.DOMESTIC,
                MarketSession.of(Market.DOMESTIC).today(clock),
                Market.OVERSEAS,
                MarketSession.of(Market.OVERSEAS).today(clock));
    }

    /** 전일 종가가 속한 DEMOTE 파티션 등급으로 유효 등급 초기값을 세팅한다 — 이미 마감한 시장은 건너뛴다. */
    private int initializeGrades(ReferenceSnapshot snapshot) {
        int initialized = 0;
        for (StockReference reference : snapshot.stocks()) {
            Duration untilClose = MarketSession.of(reference.market()).untilClose(clock);
            if (untilClose.isNegative() || untilClose.isZero()) {
                continue;
            }
            for (Map.Entry<String, HorizonBands> entry : reference.bands().entrySet()) {
                Grade initial = entry.getValue().demote().lookup(reference.prevClose()).grade();
                if (stateStore.initGradeIfAbsent(
                        reference.symbol(), entry.getKey(), initial, untilClose)) {
                    initialized++;
                }
            }
        }
        return initialized;
    }
}
