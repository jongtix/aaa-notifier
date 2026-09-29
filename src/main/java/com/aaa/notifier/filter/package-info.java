/**
 * 알림 필터/게이팅 파이프라인 (SPEC-NOTIFIER-FILTER-001).
 *
 * <p>소비 계층의 {@code StreamObservationHandler} 포트를 {@link com.aaa.notifier.filter.FilterPipeline}이
 * 구현한다. 틱 수신만이 구동원이며, 단계는 밴드 판정(이원 경계 = 히스테리시스) → 가드 3종 → 전환 유형 분류 → 확증 → 쿨다운 → confidence 방향성 →
 * Tier 분류 순이다. 결정은 {@link com.aaa.notifier.filter.AlertDecisionSink} 포트로 나가며, 기본 구현은 {@code
 * notification_log} DRYRUN INSERT다 — 텔레그램 발송·SENT류 기록·{@code stream:alert} 발행은 TELEGRAM-001 소관이다.
 *
 * <p>DB 접근은 장전 적재 SELECT 3종({@link com.aaa.notifier.filter.ReferenceDataLoader})과 DRYRUN INSERT
 * ({@link com.aaa.notifier.filter.JdbcDryRunAlertDecisionSink})뿐이다. 틱 경로는 DB를 조회하지 않는다. 필터 상태는
 * {@code filter:*:{종목코드}:{horizon}} Redis 키에 둔다({@link com.aaa.notifier.filter.FilterKeys}).
 */
package com.aaa.notifier.filter;
