package com.aaa.notifier.filter;

import com.aaa.notifier.stream.TradeTickObservation;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * 한 체결에서 한 horizon의 전환 후보가 감지된 상황 — 밴드 판정({@link FilterPipeline})이 게이트({@link TransitionGate})에 넘기는
 * 입력.
 *
 * @param observation 파이프라인을 구동한 체결
 * @param reference 종목 참조 데이터
 * @param horizon {@code "D20"}/{@code "D60"}
 * @param pending 이 감지가 이어가는 확증 대기 후보
 * @param tradeAt 체결 시각
 * @param tradeDate 시장 현지 거래일
 * @param intradayRange 당일 누적 변동폭(고가 − 저가)
 * @param untilClose 장 마감까지 남은 시간 — 장중 상태 키 TTL
 * @param traceId 결정에 실을 추적 ID
 */
record Detection(
        TradeTickObservation observation,
        StockReference reference,
        String horizon,
        PendingTransition pending,
        ZonedDateTime tradeAt,
        LocalDate tradeDate,
        BigDecimal intradayRange,
        Duration untilClose,
        String traceId) {

    String symbol() {
        return reference.symbol();
    }
}
