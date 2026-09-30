package com.aaa.notifier.telegram;

import com.aaa.notifier.filter.AlertDecision;
import com.aaa.notifier.filter.Grade;
import com.aaa.notifier.filter.SuppressionReason;
import com.aaa.notifier.filter.TransitionType;
import com.aaa.notifier.stream.Market;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 텔레그램 테스트용 결정 픽스처. */
final class TelegramAlerts {

    static final LocalDate TRADE_DATE = LocalDate.of(2026, 9, 29);

    private TelegramAlerts() {}

    /** AC-05 기준 후보 — BRK.B·D20·BUY→STRONG_BUY·Tier 1·score -0.043·confidence 0.612·412.5000·저확신. */
    static AlertDecision acFive() {
        return new AlertDecision(
                7L,
                "BRK.B",
                Market.OVERSEAS,
                "D20",
                Grade.BUY,
                Grade.STRONG_BUY,
                1,
                null,
                new BigDecimal("-0.043"),
                new BigDecimal("0.612"),
                true,
                new BigDecimal("412.5000"),
                new BigDecimal("410.0000"),
                TRADE_DATE,
                null,
                "trace-ac05");
    }

    /** 일반 발송 후보 (국내 HOLD→BUY, Tier 2). */
    static AlertDecision candidate(String traceId) {
        return candidate(traceId, "005930", "D20");
    }

    static AlertDecision candidate(String traceId, String symbol, String horizon) {
        return new AlertDecision(
                1L,
                symbol,
                Market.DOMESTIC,
                horizon,
                Grade.HOLD,
                Grade.BUY,
                2,
                TransitionType.HOLD_ENTRY,
                new BigDecimal("0.043000"),
                new BigDecimal("0.712"),
                false,
                new BigDecimal("30600.0000"),
                new BigDecimal("30000.0000"),
                TRADE_DATE,
                null,
                traceId);
    }

    /** score·confidence 미수신 후보. */
    static AlertDecision withoutSignal(String traceId) {
        AlertDecision base = candidate(traceId);
        return new AlertDecision(
                base.stockId(),
                base.symbol(),
                base.market(),
                base.horizon(),
                base.fromGrade(),
                base.toGrade(),
                base.tier(),
                base.transitionType(),
                null,
                null,
                false,
                base.triggerPrice(),
                base.prevClose(),
                base.tradeDate(),
                null,
                traceId);
    }

    /** 억제 결정 (쿨다운). */
    static AlertDecision suppressed(String traceId) {
        AlertDecision base = candidate(traceId);
        return new AlertDecision(
                base.stockId(),
                base.symbol(),
                base.market(),
                base.horizon(),
                base.fromGrade(),
                base.toGrade(),
                base.tier(),
                base.transitionType(),
                base.score(),
                base.confidence(),
                false,
                base.triggerPrice(),
                base.prevClose(),
                base.tradeDate(),
                SuppressionReason.COOLDOWN_ACTIVE,
                traceId);
    }

    /** 심볼만 바꾼 후보 (이스케이프·길이 검사용). */
    static AlertDecision withSymbol(String symbol) {
        return candidate("trace-symbol", symbol, "D60");
    }
}
