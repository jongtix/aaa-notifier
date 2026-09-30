package com.aaa.notifier.telegram;

import com.aaa.notifier.filter.AlertDecision;
import com.aaa.notifier.filter.MarketSession;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;

/**
 * 단일 이관 지점 (design.md §4.1) — 알림이 "대기 큐로 간다"고 쓴 모든 경로(요청 직전 safe_mode·429 상한·재시도 소진·버퍼 초과·종료 잔량)가
 * 여기를 지난다.
 *
 * <p>관측한 safe_mode가 운영자 출처 {@code ON}이면 대기 큐 대신 FILTER-001 DRYRUN 행만 남긴다 — {@code QUEUED} 행·{@code
 * stream:alert}·해제 후 요약 모두 없다([F-11]). 그 밖이면 대기 큐 뒤에 붙이고 {@code QUEUED} 행을 남긴다. 한 곳에 모았기 때문에 새 이관
 * 경로가 생겨도 kill switch를 빠뜨릴 수 없다(research.md §4.3 ②).
 *
 * <p>틱 소비 스레드(버퍼 초과)와 디스패처가 동시에 부를 수 있다 — 상태를 갖지 않고 협력자는 모두 스레드 안전하다.
 */
// @MX:ANCHOR: [AUTO] kill switch(ON)가 대기 큐 행 경로를 막는 유일한 지점 — 이관 경로 5곳이 모두 여기를 지난다
// @MX:REASON: 경로 하나라도 이 메서드를 우회하면 운영자 kill switch 기간의 알림이 대기 큐에 들어가 해제 후 요약으로 나간다([F-11], D6)
@Slf4j
final class QueueTransfer {

    private final DeliveryContext context;

    QueueTransfer(DeliveryContext context) {
        this.context = context;
    }

    /**
     * @param decision 발송 후보
     * @param reason 이관 사유
     * @param observed 이 경로가 관측한 safe_mode (경로별 관측 방법은 design.md §4.2)
     */
    void divert(AlertDecision decision, DivertReason reason, SafeModeState observed) {
        if (observed == SafeModeState.ON) {
            // FILTER-001 DRYRUN 싱크가 행 INSERT와 결정 계측을 함께 한다 — 여기서 결정 계측을 또 올리면 이중 계산이다(design.md
            // §4.3)
            context.dryRunSink().onDecision(decision);
            log.info(
                    "[telegram-dispatch] 운영자 kill switch(ON) — 발송 대신 DRYRUN 기록 trace_id={} reason={}",
                    decision.traceId(),
                    reason);
            return;
        }
        QueuedAlert item =
                QueuedAlert.of(
                        decision,
                        LocalDateTime.now(context.clock().withZone(MarketSession.KST)),
                        reason);
        try {
            context.queue().append(item);
            context.metrics().queueDepth(context.queue().size());
            context.notificationLog()
                    .alert(
                            EventType.QUEUED,
                            decision,
                            context.formatter().formatAlert(decision),
                            null);
            log.info(
                    "[telegram-dispatch] 대기 큐 적재 trace_id={} reason={} safe_mode={}",
                    decision.traceId(),
                    reason,
                    observed);
        } catch (DataAccessException e) {
            context.metrics().queueWriteFailure();
            log.error(
                    "[telegram-dispatch] 대기 큐 적재 실패 — 이 알림은 유실된다(plan.md §H R8) trace_id={} reason={} error={}",
                    decision.traceId(),
                    reason,
                    e.getMessage());
        }
        context.filterMetrics().decision(decision);
    }
}
