package com.aaa.notifier.filter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code filter:signal:{종목코드}:{horizon}} — 당일 신호 스냅샷 (설계 초안 [D-6], REQ-063 후단).
 *
 * <p>신호 도착마다 통째로 덮어쓴다. confidence 추세(여러 날 누적)는 갱신 시맨틱이 달라 별도 키로 둔다(REQ-051, plan.md §E.1).
 *
 * @param signalClass 신호 등급 원문
 * @param score 앙상블 기대수익률
 * @param confidence 신뢰도
 * @param tradeDate 신호 거래일
 * @param traceId analyzer가 발급한 추적 ID
 */
public record SignalSnapshot(
        String signalClass,
        BigDecimal score,
        BigDecimal confidence,
        LocalDate tradeDate,
        String traceId) {}
