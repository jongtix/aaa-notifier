package com.aaa.notifier.filter;

import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 결정 싱크 기본 구현 — {@code notification_log} DRYRUN INSERT (REQ-062, plan.md §C.1/§C.3, design.md §3).
 *
 * <p>발송 후보·억제 후보 <b>모두</b> {@code event_type='DRYRUN'} 행 1건을 남긴다. 텔레그램 발송은 하지 않으므로 {@code
 * message_text}·{@code telegram_message_id}는 INSERT에서 생략한다(NULL). 억제 사유는 전용 컬럼이 없어 WARN 구조화 로그로
 * 보완한다 — DB는 "무엇이 결정되었는가", 로그는 "왜 억제되었는가"를 맡는다.
 *
 * <p>SENT/SEND_FAILED/QUEUED/SUMMARY_SENT 행은 TELEGRAM-001이 같은 {@code trace_id}로 <b>추가</b>
 * INSERT한다(이벤트 소싱 — 갱신 아님, REQ-063).
 */
@Slf4j
@RequiredArgsConstructor
public class JdbcDryRunAlertDecisionSink implements AlertDecisionSink {

    /** 최초 1회 + 재시도 2회 (plan.md §C.3). */
    private static final int MAX_ATTEMPTS = 3;

    static final String INSERT_SQL =
            "INSERT INTO notification_log (event_type, notification_type, stock_id, horizon, tier,"
                    + " signal_class, score, confidence, trigger_price, prev_close, trace_id,"
                    + " trade_date, created_at)"
                    + " VALUES ('DRYRUN', 'TIMING', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;
    private final FilterMetrics metrics;
    private final Clock clock;

    @Override
    public void onDecision(AlertDecision decision) {
        persist(decision);
        if (!decision.isCandidate()) {
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
        }
        metrics.decision(decision);
    }

    /**
     * 최대 3회 시도한다. 재시도 사이에 지연을 두지 않는다 — INSERT가 틱 소비 스레드에서 동기 호출되므로 sleep은 그대로 틱 처리 지연이 된다(최악 ≤3× 단일
     * INSERT 왕복, plan.md §C.3). 3회 모두 실패하면 예외를 삼키고 실패 카운터로 폴백한다(fail-open, §H R1).
     */
    // @MX:WARN: [AUTO] 틱 소비 스레드에서 동기 DB 쓰기 — DB 장애 시 결정 1건당 최대 3 × connection-timeout만큼 틱 처리가 묶인다
    // @MX:REASON: plan.md §C.3 사용자 확정(3회·지연 없음·fail-open). 커넥션 대기 상한은 application.yml
    // hikari.connection-timeout이 결정한다
    private void persist(AlertDecision decision) {
        DataAccessException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                jdbcTemplate.update(
                        INSERT_SQL,
                        decision.stockId(),
                        decision.horizon(),
                        decision.tier(),
                        decision.toGrade().name(),
                        decision.score(),
                        decision.confidence(),
                        decision.triggerPrice(),
                        decision.prevClose(),
                        decision.traceId(),
                        decision.tradeDate(),
                        LocalDateTime.now(clock.withZone(MarketSession.KST)));
                return;
            } catch (DataAccessException e) {
                lastFailure = e;
            }
        }
        metrics.persistFailure();
        log.warn(
                "[filter-decision] DRYRUN INSERT {}회 모두 실패 — 결정 기록 누락, 틱 처리는 계속 trace_id={} symbol={} horizon={}",
                MAX_ATTEMPTS,
                decision.traceId(),
                decision.symbol(),
                decision.horizon(),
                lastFailure);
    }
}
