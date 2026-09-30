package com.aaa.notifier.telegram;

import com.aaa.notifier.filter.AlertDecision;
import com.aaa.notifier.filter.AlertDecisionSink;
import com.aaa.notifier.filter.FilterMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결정 싱크 실발송 구현 (REQ-002, plan.md §D.1) — 실발송 모드가 켜지면 FILTER-001 DRYRUN 싱크 대신 등록된다. 파이프라인·포트는 무변경이다.
 *
 * <p>발송 후보는 디스패처 버퍼에 넣고 즉시 돌아간다(REQ-004). 억제 결정은 FILTER-001과 같은 형식의 WARN 로그와 결정 계측만 남기고 {@code
 * notification_log} 행을 쓰지 않는다 — 행 = 발송 사건이다([D-5]). 이는 safe_mode 상태와 무관하다.
 */
@Slf4j
@RequiredArgsConstructor
public class TelegramAlertDecisionSink implements AlertDecisionSink {

    private final TelegramDispatcher dispatcher;
    private final FilterMetrics filterMetrics;

    @Override
    public void onDecision(AlertDecision decision) {
        if (decision.isCandidate()) {
            dispatcher.submit(decision);
            return;
        }
        log.warn(
                "[filter-decision] 억제 trace_id={} symbol={} horizon={} {}→{} tier={} type={} reason={}",
                decision.traceId(),
                decision.symbol(),
                decision.horizon(),
                decision.fromGrade(),
                decision.toGrade(),
                decision.tier(),
                decision.transitionType(),
                decision.suppressionReason());
        filterMetrics.decision(decision);
    }
}
