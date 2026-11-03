package com.aaa.notifier.filter;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * {@code signal_price_curves.curve} JSON 배열 원소 1건 — 그리드 포인트의 (가격, score) 쌍
 * (SPEC-SIGNAL-PRICECURVE-001 REQ-003).
 *
 * @param price 그리드 포인트 가격
 * @param score 그 가격에서의 기대수익률(score) — 억제 적용 전 원값(REQ-005)
 */
public record PricePoint(
        @JsonProperty("price") BigDecimal price, @JsonProperty("score") BigDecimal score) {

    public PricePoint {
        Objects.requireNonNull(price, "price must not be null");
        Objects.requireNonNull(score, "score must not be null");
    }
}
