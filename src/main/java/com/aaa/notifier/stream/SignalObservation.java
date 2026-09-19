package com.aaa.notifier.stream;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 신호 관측치 (REQ-NOTIFIER-CONSUMER-041, plan.md §B.3).
 *
 * <p>analyzer {@code inference/publish.py}의 {@code SIGNAL_STREAM_FIELDS} 7필드와 1:1 대응한다.
 *
 * <p><b>{@link #horizon()}은 {@code "D20"}/{@code "D60"} 문자열 그대로 보존한다 — enum 변환 금지.</b> 하류가 이 값을
 * {@code signal_price_bands} 조회 키로 무변환 사용하기 때문이며(analyzer {@code format_horizon_label()}이 같은 라벨을
 * {@code trading_signals}에도 쓴다), 변환을 끼우면 조회 키가 조용히 어긋난다.
 *
 * @param symbol DB {@code stocks.symbol} 표기 (해외도 {@code AAPL} — tr_key 표기가 아님)
 * @param horizon {@code "D20"} 또는 {@code "D60"} — 무변환 보존
 * @param tradeDate 거래일 (ISO 8601)
 * @param traceId analyzer가 발급한 추적 ID
 * @param signalClass 5클래스 등급
 * @param score 앙상블 기대수익률
 * @param confidence 신뢰도
 */
public record SignalObservation(
        String symbol,
        String horizon,
        LocalDate tradeDate,
        String traceId,
        String signalClass,
        BigDecimal score,
        BigDecimal confidence) {

    /** 7필드 전부가 하류 소비 대상이므로 부재를 생성 시점에 차단한다. */
    public SignalObservation {
        Objects.requireNonNull(symbol, "symbol must not be null");
        Objects.requireNonNull(horizon, "horizon must not be null");
        Objects.requireNonNull(tradeDate, "tradeDate must not be null");
        Objects.requireNonNull(signalClass, "signalClass must not be null");
        Objects.requireNonNull(score, "score must not be null");
        Objects.requireNonNull(confidence, "confidence must not be null");
    }
}
