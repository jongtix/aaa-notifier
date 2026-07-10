/**
 * 로깅·Trace ID 유틸리티 패키지.
 *
 * <p>ECS JSON 구조화 로그(KST 이중 보장, ADR-009/011)에 실릴 Trace ID를 발급·전파하는 유틸리티를 담는다: {@link
 * com.aaa.notifier.common.logging.TraceIdManager}(발급/조회/전파), {@link
 * com.aaa.notifier.common.logging.SafeMdc}(MDC 래퍼). Bot Token 마스킹 프레임워크의 실제 적용은 TELEGRAM-001
 * 소관이다(현재 마스킹할 대상 없음).
 */
package com.aaa.notifier.common.logging;
