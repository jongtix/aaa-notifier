package com.aaa.notifier.filter;

import com.aaa.notifier.filter.FilterMetrics.Outcome;
import com.aaa.notifier.filter.FilterMetrics.Stage;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;

/**
 * 전환 후보 게이트 — 가드 → 확증 → 쿨다운 → confidence 방향성 → 결정 방출 (REQ-024/031~035/042, design.md §1 2~8단계).
 *
 * <p><b>억제의 두 종류.</b> 가드 차단·확증 미달은 후보를 <b>대기</b>시킨다 — 후보는 남고 다음 감지에서 다시 평가된다. 쿨다운은 후보를 <b>종결</b>시킨다
 * — 이 전환은 알리지 않는다. 발송 후보가 확정돼도 후보는 종결된다.
 *
 * <p><b>억제 결정의 방출 빈도.</b> 대기 억제는 사유가 바뀔 때만 결정 객체를 방출한다 — 개장 직후 15분처럼 가드가 수백 틱 동안 이어지는 구간에서 틱마다
 * DRYRUN 행을 남기면 {@code notification_log}가 억제 기록으로 범람한다. 통과·차단 횟수 자체는 단계 카운터가 매 틱 센다.
 */
// @MX:NOTE: [AUTO] 대기 억제(가드·확증 미달)는 사유가 바뀔 때만 결정을 방출한다 — 틱마다 DRYRUN 행을 남기지 않기 위한 설계 선택
@RequiredArgsConstructor
public class TransitionGate {

    private final FilterStateStore stateStore;
    private final GuardEvaluator guards;
    private final TransitionPolicy policy;
    private final ConfidencePolicy confidencePolicy;
    private final AlertDecisionSink sink;
    private final FilterMetrics metrics;

    /**
     * 감지 1건을 평가한다.
     *
     * @param detection 전환 후보 감지 — {@code pending.from() != pending.to()}가 보장된다
     */
    public void evaluate(Detection detection) {
        PendingTransition pending = detection.pending();
        TransitionClass cell =
                TransitionMatrix.classify(pending.from(), pending.to()).orElseThrow();

        Optional<SuppressionReason> guardBlock =
                guards.evaluate(
                        detection.reference(),
                        detection.tradeAt(),
                        detection.observation().accumulatedVolume(),
                        detection.intradayRange());
        if (guardBlock.isPresent()) {
            hold(detection, cell, pending, guardBlock.get());
            return;
        }

        PendingTransition counted = pending.counted();
        if (counted.count() < policy.confirmations(cell)) {
            metrics.stage(Stage.CONFIRM, Outcome.BLOCK);
            hold(detection, cell, counted, SuppressionReason.CONFIRM_PENDING);
            return;
        }
        metrics.stage(Stage.CONFIRM, Outcome.PASS);

        if (cooldownActive(detection, cell)) {
            metrics.stage(Stage.COOLDOWN, Outcome.BLOCK);
            close(detection, cell, SuppressionReason.COOLDOWN_ACTIVE);
            return;
        }
        metrics.stage(Stage.COOLDOWN, Outcome.PASS);

        // Tier 구분 없이 균일 적용 — Tier 1(STRONG 도달)도 예외가 아니다(REQ-052, plan.md §C.2)
        if (confidencePolicy.isWeakening(
                stateStore.confidences(detection.symbol(), detection.horizon()))) {
            metrics.stage(Stage.CONFIDENCE, Outcome.BLOCK);
            close(detection, cell, SuppressionReason.CONFIDENCE_WEAKENING_DEMOTED);
            return;
        }
        metrics.stage(Stage.CONFIDENCE, Outcome.PASS);

        close(detection, cell, null);
        Duration cooldown = policy.cooldownOf(cell);
        if (cooldown.isPositive()) {
            stateStore.startCooldown(
                    detection.symbol(), detection.horizon(), cell.tier(), pending.to(), cooldown);
        }
    }

    /**
     * 쿨다운 검사 (REQ-034) — 같은 종목·horizon·Tier의 쿨다운 키가 살아 있으면 억제한다. 쿨다운 0(직접 전환)은 검사하지 않는다. 순수 강도 강화
     * 칸은 "Tier-1 같은 방향 반복만" 막는다(REQ-035) — 반대 방향 STRONG 도달은 막지 않는다.
     */
    private boolean cooldownActive(Detection detection, TransitionClass cell) {
        if (!policy.cooldownOf(cell).isPositive()) {
            return false;
        }
        Optional<Grade> active =
                stateStore.cooldown(detection.symbol(), detection.horizon(), cell.tier());
        return active.isPresent()
                && (cell.kind() != TransitionClass.Kind.STRENGTHEN
                        || active.get() == detection.pending().to());
    }

    /** 후보를 대기 상태로 남긴다 — 억제 사유가 바뀔 때만 결정을 방출한다. */
    private void hold(
            Detection detection,
            TransitionClass cell,
            PendingTransition pending,
            SuppressionReason reason) {
        boolean reasonChanged = pending.lastReason() != reason;
        stateStore.savePending(
                detection.symbol(),
                detection.horizon(),
                pending.withReason(reason),
                detection.untilClose());
        metrics.pendingOpened(
                detection.reference().market(), detection.symbol(), detection.horizon());
        if (reasonChanged) {
            sink.onDecision(decision(detection, cell, reason));
        }
    }

    /** 후보를 종결하고 결정을 방출한다. {@code reason}이 {@code null}이면 발송 후보다. */
    private void close(Detection detection, TransitionClass cell, SuppressionReason reason) {
        stateStore.clearPending(detection.symbol(), detection.horizon());
        metrics.pendingClosed(
                detection.reference().market(), detection.symbol(), detection.horizon());
        AlertDecision decision = decision(detection, cell, reason);
        if (decision.isCandidate() && decision.lowConfidenceFlag()) {
            metrics.stage(Stage.CONFIDENCE, Outcome.LOW_CONFIDENCE_TAGGED);
        }
        sink.onDecision(decision);
    }

    private AlertDecision decision(
            Detection detection, TransitionClass cell, SuppressionReason reason) {
        Optional<SignalSnapshot> signal =
                stateStore.signal(detection.symbol(), detection.horizon());
        BigDecimal score = signal.map(SignalSnapshot::score).orElse(null);
        BigDecimal confidence = signal.map(SignalSnapshot::confidence).orElse(null);
        StockReference reference = detection.reference();
        return new AlertDecision(
                reference.stockId(),
                reference.symbol(),
                reference.market(),
                detection.horizon(),
                detection.pending().from(),
                detection.pending().to(),
                cell.tier(),
                cell.type(),
                score,
                confidence,
                confidencePolicy.isLow(confidence),
                detection.observation().price(),
                reference.prevClose(),
                detection.tradeDate(),
                reason,
                detection.traceId());
    }
}
