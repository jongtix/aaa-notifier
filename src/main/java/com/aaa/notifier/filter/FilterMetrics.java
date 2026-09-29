package com.aaa.notifier.filter;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
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
    static final String STAGE_COUNTER = "notifier.filter.stage";
    static final String DECISION_COUNTER = "notifier.filter.decision";
    static final String PERSIST_FAILURE = "notifier.filter.decision.persist.failure";

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

    /**
     * 파이프라인 단계별 깔때기 카운터 (REQ-071) — {@code notifier_filter_stage_total{stage, outcome}}.
     *
     * @param stage 파이프라인 단계
     * @param outcome 그 단계의 판정 결과
     */
    public void stage(Stage stage, Outcome outcome) {
        registry.counter(STAGE_COUNTER, "stage", stage.tag(), OUTCOME, outcome.tag()).increment();
    }

    /**
     * 결정 1건을 기록한다 — {@code notifier_filter_decision_total{outcome, suppression_reason}}.
     *
     * @param decision 발송 후보 또는 억제 결정
     */
    public void decision(AlertDecision decision) {
        SuppressionReason reason = decision.suppressionReason();
        registry.counter(
                        DECISION_COUNTER,
                        OUTCOME,
                        reason == null ? "candidate" : "suppressed",
                        "suppression_reason",
                        reason == null ? "none" : reason.name().toLowerCase(Locale.ROOT))
                .increment();
    }

    /** DRYRUN INSERT가 3회 모두 실패했다(plan.md §C.3). */
    public void persistFailure() {
        registry.counter(PERSIST_FAILURE).increment();
    }

    /** 장전 적재 1회의 결과를 기록한다. */
    public void referenceLoad(boolean success, int stockCount) {
        registry.counter(REFERENCE_LOAD, OUTCOME, success ? "success" : "failure").increment();
        if (success) {
            loadedStocks.set(stockCount);
        }
    }

    /** 파이프라인 단계 (design.md §6 {@code stage} 태그). */
    public enum Stage {
        BAND,
        GUARD_VOLUME,
        GUARD_TIME,
        GUARD_ATR,
        CONFIRM,
        COOLDOWN,
        CONFIDENCE;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 단계 판정 결과 ({@code outcome} 태그). */
    public enum Outcome {
        /** 통과. */
        PASS,
        /** 차단(억제). */
        BLOCK,
        /** 이원 경계 불일치 — 유효 등급 유지(REQ-011). */
        DEAD_ZONE,
        /** 유효 등급 전환 감지(REQ-011). */
        TRANSITION,
        /** 그리드 밖 가격 클램프(REQ-014). */
        CLAMPED,
        /** {@code price.scale() != priceScale} 관측치 제외(REQ-015). */
        SKIPPED_SCALE_MISMATCH,
        /** 발송 후보에 저확신 표시 플래그 부여(REQ-053). */
        LOW_CONFIDENCE_TAGGED;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
