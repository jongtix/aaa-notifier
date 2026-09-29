package com.aaa.notifier.filter;

import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;

/** 단위 테스트용 파이프라인 조립 — plan.md §F 잠정값과 같은 수치의 설정으로 실제 구성 요소를 엮는다. */
final class FilterPipelines {

    /** plan.md §F 잠정값 (application.yml과 동일). */
    static final FilterProperties.Guard GUARD =
            new FilterProperties.Guard(
                    new BigDecimal("0.30"),
                    Duration.ofMinutes(15),
                    Duration.ofMinutes(10),
                    new BigDecimal("2.5"));

    static final FilterProperties.Confirm CONFIRM = new FilterProperties.Confirm(2, 3, 5);

    static final FilterProperties.Cooldown COOLDOWN =
            new FilterProperties.Cooldown(
                    Duration.ZERO,
                    Duration.ofMinutes(10),
                    Duration.ofMinutes(30),
                    Duration.ofMinutes(20),
                    Duration.ofMinutes(10));

    private FilterPipelines() {}

    static FilterPipeline create(
            ReferenceDataHolder holder,
            FilterStateStore store,
            AlertDecisionSink sink,
            MeterRegistry registry,
            Clock clock) {
        FilterMetrics metrics = FilterMetrics.create(registry);
        TransitionGate gate =
                new TransitionGate(
                        store,
                        new GuardEvaluator(GUARD, metrics),
                        new TransitionPolicy(CONFIRM, COOLDOWN),
                        sink,
                        metrics);
        return new FilterPipeline(holder, store, new IntradayTracker(), gate, metrics, clock);
    }
}
