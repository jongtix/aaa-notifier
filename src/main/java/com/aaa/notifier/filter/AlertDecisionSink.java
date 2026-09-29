package com.aaa.notifier.filter;

/**
 * 결정 싱크 포트 — 파이프라인의 단일 하류 경계 (REQ-NOTIFIER-FILTER-062, plan.md §B).
 *
 * <p>소비 스레드에서 <b>동기적으로</b> 호출된다. 기본 구현은 {@link JdbcDryRunAlertDecisionSink}({@code
 * notification_log} DRYRUN INSERT, 발송 없음)이며, TELEGRAM-001은 이 인터페이스의 실발송 구현체를 빈으로 올리기만 하면 된다 — 파이프라인
 * 코드는 무변경이다 (CONSUMER-001이 {@code StreamObservationHandler}에 적용한 패턴과 동일).
 */
// @MX:ANCHOR: [AUTO] FILTER-001 ↔ TELEGRAM-001 계약면 — 파이프라인의 모든 결정(발송·억제)이 이 한 메서드로 나간다
// @MX:REASON: 시그니처·호출 시점(동기, 억제 결정 포함)을 바꾸면 하류 구현체와 DRYRUN 기록이 함께 깨진다
@FunctionalInterface
public interface AlertDecisionSink {

    /**
     * 결정 1건을 소비한다. 구현은 틱 처리 경로를 막지 않도록 예외를 전파하지 않아야 한다.
     *
     * @param decision 불변 결정 객체
     */
    void onDecision(AlertDecision decision);
}
