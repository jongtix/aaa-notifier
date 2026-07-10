/**
 * Trace 전파 패키지 경계(구조적 자리).
 *
 * <p>Trace ID 발급·MDC 관리 유틸리티는 {@link com.aaa.notifier.common.logging}에 위치한다(collector 이식 정합). 이
 * 패키지는 스트림 소비 경계·Virtual Thread 자식 스레드로의 Trace 전파 래퍼가 후속 SPEC(CONSUMER-001/FILTER-001)에서 필요해질 때를 위한
 * 구조적 자리다.
 */
package com.aaa.notifier.common.trace;
