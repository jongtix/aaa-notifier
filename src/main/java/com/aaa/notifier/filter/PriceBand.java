package com.aaa.notifier.filter;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * {@code signal_price_bands} 파티션 1행 — 가격 구간과 그 구간의 등급.
 *
 * @param low 파티션 하한가 ({@code price_low})
 * @param high 파티션 상한가 ({@code price_high})
 * @param grade 구간 등급 ({@code signal_class})
 */
public record PriceBand(BigDecimal low, BigDecimal high, Grade grade) {

    public PriceBand {
        Objects.requireNonNull(low, "low must not be null");
        Objects.requireNonNull(high, "high must not be null");
        Objects.requireNonNull(grade, "grade must not be null");
    }
}
