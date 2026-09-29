package com.aaa.notifier.filter;

import com.aaa.notifier.filter.FilterMetrics.Outcome;
import com.aaa.notifier.filter.FilterMetrics.Stage;
import com.aaa.notifier.stream.QuoteObservation;
import com.aaa.notifier.stream.SignalObservation;
import com.aaa.notifier.stream.StreamObservationHandler;
import com.aaa.notifier.stream.TradeTickObservation;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;

/**
 * 필터/게이팅 파이프라인 — {@link StreamObservationHandler} 포트의 첫 실동작 구현 (SPEC-NOTIFIER-FILTER-001).
 *
 * <p><b>틱 수신({@link #onTradeTick})만이 파이프라인의 유일한 구동원이다</b>(REQ-063). 신호 수신은 {@code filter:signal} 상태
 * 갱신만 하고, 호가는 해석하지 않는다(spec.md §3).
 *
 * <p><b>틱 경로는 DB를 조회하지 않는다</b>(REQ-002) — 참조 데이터·밴드는 장전 적재 스냅샷({@link ReferenceDataHolder})만 읽는다.
 *
 * <p>소비 스레드에서 동기 호출되며 확인응답(XACK) 이전에 끝나야 한다({@link StreamObservationHandler} 계약) — 느려지면 틱 스트림이
 * 트리밍으로 소실된다.
 */
// @MX:ANCHOR: [AUTO] 틱·신호 관측치의 유일한 필터 진입점 — 소비 워커 4개(스트림별)가 모두 이 구현을 호출한다
// @MX:REASON: 판정 순서(밴드 → 가드 → 유형 → 확증 → 쿨다운 → confidence → Tier)를 바꾸면 억제 사유·확증 누적이 조용히 달라진다
@RequiredArgsConstructor
public class FilterPipeline implements StreamObservationHandler {

    private final ReferenceDataHolder holder;
    private final FilterStateStore stateStore;
    private final FilterMetrics metrics;
    private final Clock clock;

    @Override
    public void onTradeTick(TradeTickObservation observation) {
        if (isScaleMismatch(observation)) {
            // CONSUMER-001 REQ-051 재검출 의무 — 비스케일 정수를 소수 가격으로 오인하지 않는다(REQ-015)
            metrics.stage(Stage.BAND, Outcome.SKIPPED_SCALE_MISMATCH);
            return;
        }
        holder.current()
                .find(observation.market(), observation.symbol())
                .ifPresent(reference -> evaluate(observation, reference));
    }

    @Override
    public void onQuote(QuoteObservation observation) {
        // 호가 필드는 해석하지 않는다 — 파이프라인을 구동하지 않는다(spec.md §3 Out of Scope)
    }

    @Override
    public void onSignal(SignalObservation observation) {
        stateStore.saveSignal(
                observation.symbol(),
                observation.horizon(),
                new SignalSnapshot(
                        observation.signalClass(),
                        observation.score(),
                        observation.confidence(),
                        observation.tradeDate(),
                        observation.traceId()));
    }

    private static boolean isScaleMismatch(TradeTickObservation observation) {
        Integer priceScale = observation.priceScale();
        return priceScale != null && observation.price().scale() != priceScale;
    }

    private void evaluate(TradeTickObservation observation, StockReference reference) {
        Duration untilClose = MarketSession.of(reference.market()).untilClose(clock);
        if (untilClose.isNegative() || untilClose.isZero()) {
            return;
        }
        for (Map.Entry<String, HorizonBands> entry : reference.bands().entrySet()) {
            judgeBand(observation, reference, entry.getKey(), entry.getValue(), untilClose);
        }
    }

    /**
     * 이원 경계 밴드 판정 (REQ-011/014) — 이 판정이 히스테리시스 구현의 전부다(TECHSPEC §8.2).
     *
     * <p>PROMOTE·DEMOTE 두 파티션이 같은 등급을 가리킬 때만 유효 등급을 그 값으로 옮긴다. 불일치 구간(데드존)은 "HOLD로 전환"이 아니라 "현재 유효
     * 등급 유지"다.
     */
    private void judgeBand(
            TradeTickObservation observation,
            StockReference reference,
            String horizon,
            HorizonBands bands,
            Duration untilClose) {
        String symbol = reference.symbol();
        BandLookup promote = bands.promote().lookup(observation.price());
        BandLookup demote = bands.demote().lookup(observation.price());
        if (promote.clamped() || demote.clamped()) {
            metrics.stage(Stage.BAND, Outcome.CLAMPED);
        }
        Grade current =
                stateStore
                        .grade(symbol, horizon)
                        .orElseGet(() -> initialGrade(reference, horizon, bands, untilClose));
        if (promote.grade() != demote.grade()) {
            metrics.stage(Stage.BAND, Outcome.DEAD_ZONE);
            return;
        }
        Grade agreed = promote.grade();
        if (agreed != current) {
            stateStore.setGrade(symbol, horizon, agreed, untilClose);
            metrics.stage(Stage.BAND, Outcome.TRANSITION);
        }
    }

    /** 장전 초기화를 놓친 경우(키 만료·적재 후 편입)의 초기값 — 전일 종가가 속한 DEMOTE 파티션 등급(REQ-003). */
    private Grade initialGrade(
            StockReference reference, String horizon, HorizonBands bands, Duration untilClose) {
        Grade initial = bands.demote().lookup(reference.prevClose()).grade();
        stateStore.initGradeIfAbsent(reference.symbol(), horizon, initial, untilClose);
        return initial;
    }
}
