package com.aaa.notifier.filter;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * 필터 파이프라인 계측 (REQ-NOTIFIER-FILTER-071, design.md §6).
 *
 * <p><b>게이지는 생성 시점({@link #create})에 즉시 등록한다</b> — 기동 후 첫 이벤트 전까지 시계열이 부재하면 vmalert 룰이 무장 해제되는 구멍이
 * 생긴다(collector #88 교훈, 설계 초안 [D-14]). 카운터는 Micrometer 관례상 최초 증가 시 등록된다.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class FilterMetrics {

    static final String REFERENCE_LOAD = "notifier.filter.reference.load";
    static final String REFERENCE_STOCKS = "notifier.filter.reference.stocks";

    private static final String OUTCOME = "outcome";

    private final MeterRegistry registry;
    private final AtomicInteger loadedStocks = new AtomicInteger();

    /**
     * 계측기를 만들고 게이지를 즉시 등록한다(warm-start).
     *
     * @param registry Micrometer 레지스트리
     */
    public static FilterMetrics create(MeterRegistry registry) {
        FilterMetrics metrics = new FilterMetrics(registry);
        metrics.registerGauges();
        return metrics;
    }

    private void registerGauges() {
        Gauge.builder(REFERENCE_STOCKS, loadedStocks, AtomicInteger::get)
                .description("장전 적재로 판정 가능한 종목 수 — 0이면 전 종목 밴드 판정 불가 상태(plan.md §H R3)")
                .register(registry);
    }

    /** 장전 적재 1회의 결과를 기록한다. */
    public void referenceLoad(boolean success, int stockCount) {
        registry.counter(REFERENCE_LOAD, OUTCOME, success ? "success" : "failure").increment();
        if (success) {
            loadedStocks.set(stockCount);
        }
    }
}
