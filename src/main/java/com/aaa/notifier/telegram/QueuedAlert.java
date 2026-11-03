package com.aaa.notifier.telegram;

import com.aaa.notifier.filter.AlertDecision;
import com.aaa.notifier.filter.ScoreBasis;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 대기 큐 항목 1건 — Redis List {@code queue:telegram:pending}에 JSON 문자열로 들어간다 (plan.md §D.7).
 *
 * <p>요약 목록 한 줄(시각·심볼·horizon·전환·score)과 원본 알림 추적({@code trace_id})에 필요한 만큼만 담는다(spec.md §4 가정).
 *
 * @param queuedAt 적재 시각 (KST) — 보존 기간 판정 기준
 * @param traceId 원본 결정의 추적 ID
 * @param stockId {@code stocks.id}
 * @param symbol 심볼
 * @param market 시장
 * @param horizon {@code D20}/{@code D60}
 * @param from 전환 전 등급
 * @param to 전환 후 등급
 * @param tier Tier 라벨
 * @param score score ({@code null} 가능)
 * @param confidence confidence ({@code null} 가능)
 * @param tradeDate 거래일
 * @param reason 이관 사유 (소문자)
 * @param displayScore 표시용 score (SPEC-SIGNAL-PRICECURVE-001 REQ-015) — {@link AlertDecision}과 같은
 *     값·분기 규칙을 공유한다. 이 SPEC 배포 전에 쌓인 구 큐 항목에는 이 멤버가 없어 역직렬화 시 {@code null}일 수 있다 — 쓸 때는 {@code
 *     null}이면 멤버 자체를 생략한다(Boot 3.5 형식 계약과의 바이트 호환, {@code RedisPendingQueueFormatContractTest} 참조)
 * @param scoreBasis {@code displayScore}의 근거 가격 (REQ-015) — 위와 같은 이유로 {@code null}이면 멤버를 생략한다
 */
public record QueuedAlert(
        @JsonProperty("queued_at") LocalDateTime queuedAt,
        @JsonProperty("trace_id") String traceId,
        @JsonProperty("stock_id") long stockId,
        @JsonProperty("symbol") String symbol,
        @JsonProperty("market") String market,
        @JsonProperty("horizon") String horizon,
        @JsonProperty("from") String from,
        @JsonProperty("to") String to,
        @JsonProperty("tier") int tier,
        @JsonProperty("score") BigDecimal score,
        @JsonProperty("confidence") BigDecimal confidence,
        @JsonProperty("trade_date") LocalDate tradeDate,
        @JsonProperty("reason") String reason,
        @JsonProperty("display_score") @JsonInclude(JsonInclude.Include.NON_NULL)
                BigDecimal displayScore,
        @JsonProperty("score_basis") @JsonInclude(JsonInclude.Include.NON_NULL)
                ScoreBasis scoreBasis) {

    /**
     * 결정에서 항목을 만든다.
     *
     * @param decision 발송 후보
     * @param queuedAt 적재 시각 (KST)
     * @param reason 이관 사유
     * @return 대기 큐 항목
     */
    public static QueuedAlert of(
            AlertDecision decision, LocalDateTime queuedAt, DivertReason reason) {
        return new QueuedAlert(
                queuedAt,
                decision.traceId(),
                decision.stockId(),
                decision.symbol(),
                decision.market().name(),
                decision.horizon(),
                decision.fromGrade().name(),
                decision.toGrade().name(),
                decision.tier(),
                decision.score(),
                decision.confidence(),
                decision.tradeDate(),
                reason.name().toLowerCase(Locale.ROOT),
                decision.displayScore(),
                decision.scoreBasis());
    }
}
