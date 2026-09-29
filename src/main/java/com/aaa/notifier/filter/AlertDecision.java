package com.aaa.notifier.filter;

import com.aaa.notifier.stream.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 필터 파이프라인의 최종 결정 객체 (REQ-NOTIFIER-FILTER-061, plan.md §B.1).
 *
 * <p>발송 후보({@link #suppressionReason()}이 {@code null})와 억제 후보를 같은 타입으로 싣는다 — 억제 사유도 결정의 일부로 하류에 넘겨야
 * 차단 통계를 관측할 수 있다(REPORT-001 소비 대상). 필드는 {@code notification_log} DRYRUN INSERT(REQ-062)와
 * TELEGRAM-001 실발송이 새 조회 없이 채울 수 있도록 구성했다.
 *
 * @param stockId {@code stocks.id} — 장전 적재 매핑 재사용(신규 SELECT 없음)
 * @param symbol 정규 종목 심볼
 * @param market 시장 구분
 * @param horizon {@code "D20"}/{@code "D60"} — 무변환 보존
 * @param fromGrade 전환 전 유효 등급
 * @param toGrade 전환 후 유효 등급 ({@code notification_log.signal_class})
 * @param tier Tier 라벨 1/2/3 — 발송 여부 게이트가 아니라 중요도 라벨이다(REQ-041)
 * @param transitionType 전환 유형. 순수 강도 강화(Tier 1)·강도 완화(Tier 3) 셀은 {@code null}
 * @param score 신호 score (신호 미수신이면 {@code null})
 * @param confidence 신호 confidence (신호 미수신이면 {@code null})
 * @param lowConfidenceFlag 저확신 표시 — 발송 차단이 아니라 하류 렌더링용 표시다(REQ-053)
 * @param triggerPrice 파이프라인을 구동한 체결가
 * @param prevClose 전일 종가
 * @param tradeDate 시장 현지 거래일
 * @param suppressionReason 억제 사유. {@code null}이면 발송 후보
 * @param traceId 추적 ID — {@code notification_log.trace_id}(NOT NULL), TELEGRAM-001이 같은 값으로 후속 행을
 *     잇는다
 */
public record AlertDecision(
        long stockId,
        String symbol,
        Market market,
        String horizon,
        Grade fromGrade,
        Grade toGrade,
        int tier,
        TransitionType transitionType,
        BigDecimal score,
        BigDecimal confidence,
        boolean lowConfidenceFlag,
        BigDecimal triggerPrice,
        BigDecimal prevClose,
        LocalDate tradeDate,
        SuppressionReason suppressionReason,
        String traceId) {

    public AlertDecision {
        Objects.requireNonNull(symbol, "symbol must not be null");
        Objects.requireNonNull(market, "market must not be null");
        Objects.requireNonNull(horizon, "horizon must not be null");
        Objects.requireNonNull(fromGrade, "fromGrade must not be null");
        Objects.requireNonNull(toGrade, "toGrade must not be null");
        Objects.requireNonNull(triggerPrice, "triggerPrice must not be null");
        Objects.requireNonNull(prevClose, "prevClose must not be null");
        Objects.requireNonNull(tradeDate, "tradeDate must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
    }

    /** 발송 후보(억제되지 않은 결정)인지. */
    public boolean isCandidate() {
        return suppressionReason == null;
    }
}
