package com.aaa.notifier.filter;

import com.aaa.notifier.filter.FilterMetrics.Outcome;
import com.aaa.notifier.filter.FilterMetrics.Stage;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import lombok.RequiredArgsConstructor;

/**
 * 전환 후보 게이트 — 가드 → 유지 시간 → 쿨다운 → confidence 방향성 → 결정 방출 (REQ-024/034/042, SPEC-NOTIFIER-FILTER-002
 * REQ-001/007, design.md §2.3).
 *
 * <p><b>확증은 유지 시간 하나다.</b> 전환 체결 시각부터 이 체결의 체결 시각까지가 칸의 유지 시간 이상이어야 통과한다 — 체결 건수는 쓰지 않는다. 판정은 체결이
 * 도착할 때만 일어나며 타이머가 없다(REQ-005).
 *
 * <p><b>억제의 두 종류.</b> 가드 차단·유지 시간 미충족은 후보를 <b>대기</b>시킨다 — 후보는 남고 다음 감지에서 다시 평가된다. 쿨다운은 후보를
 * <b>종결</b>시킨다 — 이 전환은 알리지 않는다. 발송 후보가 확정돼도 후보는 종결된다.
 *
 * <p><b>억제 결정의 방출 빈도.</b> 대기 억제는 (전환 쌍, 사유)당 종결 사이 구간에 1건만 방출한다({@code filter:recorded},
 * SPEC-NOTIFIER-FILTER-002 REQ-014) — 후보 단위로 세면 1틱 교차마다 새 후보가 생겨 중복 제거가 무력화되고 {@code
 * notification_log}가 억제 기록으로 범람한다(2026-09-30 하루 DRYRUN 504행). 종결 결정은 그 쌍의 이력만 비운다. 통과·차단 횟수 자체는 단계
 * 카운터가 매 틱 센다.
 */
// @MX:NOTE: [AUTO] 대기 억제(가드·유지 시간 미충족)는 (전환 쌍, 사유)당 종결 사이 구간 1건만 방출한다 — 후보가 무효화됐다 다시 열려도 같은 구간이면 다시
// 방출하지 않는다
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

        // 체결 시각끼리의 차이 — 음수(순서 뒤바뀐 체결)는 미충족, 같으면 통과(REQ-001 "이상", REQ-005)
        Duration elapsed = Duration.between(pending.since(), detection.tradeAt().toInstant());
        if (elapsed.compareTo(policy.dwellOf(cell)) < 0) {
            metrics.stage(Stage.DWELL, Outcome.BLOCK);
            hold(detection, cell, pending, SuppressionReason.DWELL_PENDING);
            return;
        }
        metrics.stage(Stage.DWELL, Outcome.PASS);

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

    /** 후보를 대기 상태로 남긴다 — 이 (전환 쌍, 사유)를 이번 종결 사이 구간에서 처음 판정할 때만 결정을 방출한다. */
    private void hold(
            Detection detection,
            TransitionClass cell,
            PendingTransition pending,
            SuppressionReason reason) {
        stateStore.savePending(
                detection.symbol(), detection.horizon(), pending, detection.untilClose());
        metrics.pendingOpened(
                detection.reference().market(), detection.symbol(), detection.horizon());
        if (stateStore.markRecorded(
                detection.symbol(),
                detection.horizon(),
                FilterKeys.recordedMember(pending.from(), pending.to(), reason),
                detection.untilClose())) {
            sink.onDecision(decision(detection, cell, reason));
        }
    }

    /** 후보를 종결하고 결정을 방출한다. {@code reason}이 {@code null}이면 발송 후보다. 그 전환 쌍의 방출 이력만 비운다(REQ-014). */
    private void close(Detection detection, TransitionClass cell, SuppressionReason reason) {
        PendingTransition pending = detection.pending();
        stateStore.clearPending(detection.symbol(), detection.horizon());
        stateStore.clearRecorded(
                detection.symbol(),
                detection.horizon(),
                Arrays.stream(SuppressionReason.values())
                        .map(each -> FilterKeys.recordedMember(pending.from(), pending.to(), each))
                        .toList());
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
