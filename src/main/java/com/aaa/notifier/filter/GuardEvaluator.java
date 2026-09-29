package com.aaa.notifier.filter;

import com.aaa.notifier.filter.FilterMetrics.Outcome;
import com.aaa.notifier.filter.FilterMetrics.Stage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;

/**
 * 가드 필터 3종 — 거래량 → 시간대 → 변동성(ATR) 순서의 단락 평가 (REQ-021/022/024, TECHSPEC §8.1 1단계 후반).
 *
 * <p>전환 감지 이후·확증 카운터 진입 이전에 적용된다. 하나라도 억제하면 나머지를 평가하지 않는다.
 *
 * <p><b>거래량 가드의 "같은 시각 기준 20일 평균"</b>: notifier가 가진 이력은 일봉({@code daily_ohlcv})뿐이라 시각별 누적 거래량 이력이
 * 없다. 그래서 20일 평균 일 거래량을 정규장 경과 비율로 안분해 "이 시각까지 평균적으로 쌓였을 누적 거래량"을 추정한다(장중 거래량을 균등 분포로 가정 — 실제로는
 * 개장·마감에 몰리는 U자형이라 오전엔 기대치를 과소, 오후엔 과대 추정한다).
 *
 * <p><b>입력 부재</b>(평균 거래량·ATR 산출 불가)는 억제로 다룬다 — 판단 근거가 없는 전환을 통과시키지 않는 과소 알림 방향(plan.md §F).
 */
// @MX:NOTE: [AUTO] 시각별 누적 거래량 이력이 없어 일 평균 × 정규장 경과 비율로 기대치를 안분한다 — U자형 장중 분포와 어긋나는 근사다
@RequiredArgsConstructor
public class GuardEvaluator {

    private static final int RATIO_SCALE = 6;

    private final FilterProperties.Guard guard;
    private final FilterMetrics metrics;

    /**
     * 가드 3종을 순서대로 평가한다.
     *
     * @param reference 종목 참조 데이터(평균 거래량·ATR)
     * @param tradeAt 체결 시각
     * @param accumulatedVolume 체결 시점 누적 거래량
     * @param intradayRange 당일 누적 변동폭(고가 − 저가, 장중 진행분)
     * @return 억제 사유. 통과면 빈 값
     */
    public Optional<SuppressionReason> evaluate(
            StockReference reference,
            ZonedDateTime tradeAt,
            long accumulatedVolume,
            BigDecimal intradayRange) {
        MarketSession session = MarketSession.of(reference.market());
        if (!record(
                Stage.GUARD_VOLUME, volumePasses(reference, session, tradeAt, accumulatedVolume))) {
            return Optional.of(SuppressionReason.GUARD_VOLUME);
        }
        if (!record(Stage.GUARD_TIME, timePasses(session, tradeAt))) {
            return Optional.of(SuppressionReason.GUARD_TIME_OF_DAY);
        }
        if (!record(Stage.GUARD_ATR, atrPasses(reference, intradayRange))) {
            return Optional.of(SuppressionReason.GUARD_ATR);
        }
        return Optional.empty();
    }

    private boolean record(Stage stage, boolean passed) {
        metrics.stage(stage, passed ? Outcome.PASS : Outcome.BLOCK);
        return passed;
    }

    private boolean volumePasses(
            StockReference reference,
            MarketSession session,
            ZonedDateTime tradeAt,
            long accumulatedVolume) {
        if (reference.averageVolume() == null) {
            return false;
        }
        BigDecimal elapsed = elapsedFraction(session, tradeAt);
        BigDecimal threshold =
                reference.averageVolume().multiply(elapsed).multiply(guard.volumeRatio());
        return BigDecimal.valueOf(accumulatedVolume).compareTo(threshold) >= 0;
    }

    /** 정규장 경과 비율 [0, 1]. */
    private static BigDecimal elapsedFraction(MarketSession session, ZonedDateTime tradeAt) {
        long elapsed =
                Math.clamp(
                        session.sinceOpen(tradeAt).toSeconds(), 0L, session.length().toSeconds());
        return BigDecimal.valueOf(elapsed)
                .divide(
                        BigDecimal.valueOf(session.length().toSeconds()),
                        RATIO_SCALE,
                        RoundingMode.HALF_UP);
    }

    private boolean timePasses(MarketSession session, ZonedDateTime tradeAt) {
        Duration sinceOpen = session.sinceOpen(tradeAt);
        Duration untilClose = session.untilClose(tradeAt);
        return sinceOpen.compareTo(guard.openExclusion()) >= 0
                && untilClose.compareTo(guard.closeExclusion()) >= 0;
    }

    private boolean atrPasses(StockReference reference, BigDecimal intradayRange) {
        return reference.atr() != null
                && intradayRange.compareTo(reference.atr().multiply(guard.atrMultiplier())) <= 0;
    }
}
