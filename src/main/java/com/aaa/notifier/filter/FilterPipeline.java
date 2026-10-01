package com.aaa.notifier.filter;

import com.aaa.notifier.common.logging.TraceIdManager;
import com.aaa.notifier.filter.FilterMetrics.Outcome;
import com.aaa.notifier.filter.FilterMetrics.Stage;
import com.aaa.notifier.stream.QuoteObservation;
import com.aaa.notifier.stream.SignalObservation;
import com.aaa.notifier.stream.StreamObservationHandler;
import com.aaa.notifier.stream.TradeTickObservation;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;

/**
 * 필터/게이팅 파이프라인 — {@link StreamObservationHandler} 포트의 첫 실동작 구현 (SPEC-NOTIFIER-FILTER-001).
 *
 * <p><b>틱 수신({@link #onTradeTick})만이 파이프라인의 유일한 구동원이다</b>(REQ-063). 신호 수신은 {@code filter:signal} 상태
 * 갱신만 하고, 호가는 해석하지 않는다(spec.md §3).
 *
 * <p>이 클래스는 1단계 밴드 판정(REQ-011~015)과 확증 대기 후보의 생성·교체를 맡고, 전환 후보가 감지되면 이후 단계(가드 → 유지 시간 → 쿨다운 → 결정)를
 * {@link TransitionGate}에 넘긴다.
 *
 * <p><b>틱 경로는 DB를 조회하지 않는다</b>(REQ-002) — 참조 데이터·밴드는 장전 적재 스냅샷({@link ReferenceDataHolder})만 읽는다.
 * 소비 스레드에서 동기 호출되며 확인응답(XACK) 이전에 끝나야 한다 — 느려지면 틱 스트림이 트리밍으로 소실된다.
 */
// @MX:ANCHOR: [AUTO] 틱·신호 관측치의 유일한 필터 진입점 — 소비 워커 4개(스트림별)가 모두 이 구현을 호출한다
// @MX:REASON: 판정 순서(밴드 → 가드 → 유형 → 유지 시간 → 쿨다운 → confidence → Tier)를 바꾸거나 기준 등급 규칙을 건드리면 억제 사유·유지
// 측정 시작점이 조용히 달라진다
@RequiredArgsConstructor
public class FilterPipeline implements StreamObservationHandler {

    private final ReferenceDataHolder holder;
    private final FilterStateStore stateStore;
    private final IntradayTracker intraday;
    private final TransitionGate gate;
    private final ConfidencePolicy confidencePolicy;
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

    /**
     * 신호 상태만 갱신한다 — 알림 결정을 내리지 않는다(REQ-063 후단, 설계 초안 [D-10]/[D-17]).
     *
     * <p>{@code filter:signal} 스냅샷은 덮어쓰고, confidence는 회전 윈도에 덧붙인다(REQ-051). 같은 거래일 신호의 재전달은 윈도에 다시
     * 쌓지 않는다 — 신호는 거래일당 1건이므로 거래일이 같으면 같은 신호다.
     */
    @Override
    public void onSignal(SignalObservation observation) {
        String symbol = observation.symbol();
        String horizon = observation.horizon();
        boolean newTradingDay =
                stateStore
                        .signal(symbol, horizon)
                        .map(previous -> !previous.tradeDate().equals(observation.tradeDate()))
                        .orElse(true);
        stateStore.saveSignal(
                symbol,
                horizon,
                new SignalSnapshot(
                        observation.signalClass(),
                        observation.score(),
                        observation.confidence(),
                        observation.tradeDate(),
                        observation.traceId()));
        if (newTradingDay) {
            stateStore.appendConfidence(
                    symbol, horizon, observation.confidence(), confidencePolicy.windowSize());
        }
    }

    private static boolean isScaleMismatch(TradeTickObservation observation) {
        Integer priceScale = observation.priceScale();
        return priceScale != null && observation.price().scale() != priceScale;
    }

    private void evaluate(TradeTickObservation observation, StockReference reference) {
        MarketSession session = MarketSession.of(reference.market());
        Duration untilClose = session.untilClose(clock);
        if (untilClose.isNegative() || untilClose.isZero()) {
            return;
        }
        ZonedDateTime tradeAt = MarketSession.tradeAt(observation.tradeTime(), clock);
        LocalDate tradeDate = tradeAt.withZoneSameInstant(session.zone()).toLocalDate();
        Optional<IntradayTracker.Snapshot> range =
                intraday.record(
                        reference.market(),
                        reference.symbol(),
                        tradeDate,
                        observation.price(),
                        observation.accumulatedVolume());
        if (range.isEmpty()) {
            return; // 재전달된 체결 — 같은 체결로 확증을 두 번 올리지 않는다
        }
        String traceId =
                observation.traceId() == null ? TraceIdManager.generate() : observation.traceId();
        BigDecimal intradayRange = range.get().range();
        reference
                .bands()
                .forEach(
                        (horizon, bands) ->
                                judgeBand(
                                        new Detection(
                                                observation,
                                                reference,
                                                horizon,
                                                null,
                                                tradeAt,
                                                tradeDate,
                                                intradayRange,
                                                untilClose,
                                                traceId),
                                        bands));
    }

    /**
     * 이원 경계 밴드 판정 (REQ-011/014, TECHSPEC §8.2).
     *
     * <p>PROMOTE·DEMOTE 두 파티션이 같은 등급을 가리킬 때만 유효 등급을 그 값으로 옮긴다. 불일치 구간(데드존)은 "HOLD로 전환"이 아니라 "현재 유효
     * 등급 유지"이며, 확증 대기 후보를 무효화하지도 유지 측정을 리셋하지도 않는다 — 데드존 체류 시간은 유지 시간에 포함된다(SPEC-NOTIFIER-FILTER-002
     * REQ-006). 유효 등급이 바뀌면 {@link #onCrossing}이 기준 등급에 맞춰 후보를 무효화·교체·생성한다.
     *
     * <p>데드존 폭이 0인 종목에서는 유효 등급이 1틱마다 뒤집힐 수 있다(2026-09-30 실측) — 그 플래핑은 여기서가 아니라 유지 확증이 알림 단에서 흡수한다.
     */
    private void judgeBand(Detection base, HorizonBands bands) {
        String symbol = base.symbol();
        String horizon = base.horizon();
        BandLookup promote = bands.promote().lookup(base.observation().price());
        BandLookup demote = bands.demote().lookup(base.observation().price());
        if (promote.clamped() || demote.clamped()) {
            metrics.stage(Stage.BAND, Outcome.CLAMPED);
        }
        Grade current =
                stateStore.grade(symbol, horizon).orElseGet(() -> initialGrade(base, bands));
        if (promote.grade() != demote.grade()) {
            metrics.stage(Stage.BAND, Outcome.DEAD_ZONE);
            return;
        }
        Grade agreed = promote.grade();
        if (agreed != current) {
            // 유효 등급은 유지 확증과 무관하게 즉시 갱신한다(SPEC-NOTIFIER-FILTER-002 REQ-003 후단)
            stateStore.setGrade(symbol, horizon, agreed, base.untilClose());
            metrics.stage(Stage.BAND, Outcome.TRANSITION);
            onCrossing(base, current, agreed);
            return;
        }
        Optional<PendingTransition> pending = stateStore.pending(symbol, horizon);
        if (pending.isEmpty()) {
            return;
        }
        if (pending.get().to() != agreed) {
            voidCandidate(base); // 유효 등급과 어긋난 묵은 후보
            return;
        }
        PendingTransition open = pending.get();
        if (open.since() == null) {
            // 배포 전 형식 후보 — 처음 평가하는 체결의 체결 시각을 전환 체결 시각으로 기록한다(SPEC-NOTIFIER-FILTER-002 REQ-011)
            open = open.withSince(base.tradeAt().toInstant());
        }
        gate.evaluate(withPending(base, open));
    }

    /**
     * 유효 등급 교차 처리 (SPEC-NOTIFIER-FILTER-002 REQ-002/003/004, design.md §2.2).
     *
     * <p>후보의 전환 전 등급은 <b>기준 등급</b>이다 — 열린 후보가 있으면 그 후보의 {@code from}, 없으면 교차 직전 유효 등급. 기준 등급으로 돌아오면
     * 후보를 결정 없이 무효화하고 역방향 후보를 열지 않는다. 다른 등급으로 가면 후보를 "기준 등급 → 새 등급"으로 교체하고 이 교차 체결부터 다시 잰다. 기준 등급이
     * 없으면 되돌아온 등급이 역방향 후보가 되어 결국 알림을 만든다(spec.md HISTORY [F-1]).
     */
    // @MX:NOTE: [AUTO] 기준 등급 = 열린 후보의 from(없으면 직전 유효 등급) — 새 키 없이 후보 Hash가 기준 등급을 겸한다(종결 시 후보 삭제가
    // 불변식을 보장)
    private void onCrossing(Detection base, Grade current, Grade agreed) {
        Optional<PendingTransition> pending = stateStore.pending(base.symbol(), base.horizon());
        Optional<PendingTransition> open = pending.filter(candidate -> candidate.to() == current);
        if (pending.isPresent() && open.isEmpty()) {
            voidCandidate(base); // 유효 등급과 어긋난 묵은 후보 — 교차 직전 유효 등급을 기준으로 새로 연다
        }
        Grade baseline = open.map(PendingTransition::from).orElse(current);
        if (open.isPresent()) {
            metrics.stage(Stage.DWELL, Outcome.VOIDED); // 기준 등급 복귀·교체 모두 버려지는 후보다(REQ-015)
            if (agreed == baseline) {
                stateStore.clearPending(base.symbol(), base.horizon());
                metrics.pendingClosed(base.reference().market(), base.symbol(), base.horizon());
                return;
            }
        }
        gate.evaluate(
                withPending(
                        base,
                        PendingTransition.started(baseline, agreed, base.tradeAt().toInstant())));
    }

    /** 후보를 결정 없이 버린다(묵은 후보 정리) — 유지 단계 무효화로 센다(REQ-015). */
    private void voidCandidate(Detection base) {
        stateStore.clearPending(base.symbol(), base.horizon());
        metrics.pendingClosed(base.reference().market(), base.symbol(), base.horizon());
        metrics.stage(Stage.DWELL, Outcome.VOIDED);
    }

    private static Detection withPending(Detection base, PendingTransition pending) {
        return new Detection(
                base.observation(),
                base.reference(),
                base.horizon(),
                pending,
                base.tradeAt(),
                base.tradeDate(),
                base.intradayRange(),
                base.untilClose(),
                base.traceId());
    }

    /** 장전 초기화를 놓친 경우(키 만료·적재 후 편입)의 초기값 — 전일 종가가 속한 DEMOTE 파티션 등급(REQ-003). */
    private Grade initialGrade(Detection base, HorizonBands bands) {
        StockReference reference = base.reference();
        Grade initial = bands.demote().lookup(reference.prevClose()).grade();
        stateStore.initGradeIfAbsent(
                reference.symbol(), base.horizon(), initial, base.untilClose());
        return initial;
    }
}
