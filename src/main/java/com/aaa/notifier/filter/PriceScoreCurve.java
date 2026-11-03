package com.aaa.notifier.filter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 한 종목·horizon의 가격-score 커브 (SPEC-SIGNAL-PRICECURVE-001 REQ-008/009) — {@link BandPartition}과 병렬
 * 위치.
 *
 * <p>그리드 각 포인트의 (가격, score) 쌍을 가격 오름차순으로 담고, 임의의 발동 체결가에서 두 최근접 그리드 포인트 사이를 선형 보간한다({@link
 * #scoreAt}). 그리드 범위 밖은 {@link BandPartition#lookup}의 클램프 의미와 대칭으로 최외곽 포인트의 score를 그대로 쓴다 — 외삽하지
 * 않는다(REQ-009).
 */
public final class PriceScoreCurve {

    private static final int INTERPOLATION_SCALE = 10;

    private final List<PricePoint> points;

    /**
     * @param pointsAscending 가격 오름차순 그리드 포인트 목록
     * @throws IllegalArgumentException 목록이 비어 있으면 — 빈 커브로는 보간할 수 없다
     */
    public PriceScoreCurve(List<PricePoint> pointsAscending) {
        if (pointsAscending.isEmpty()) {
            throw new IllegalArgumentException("가격-score 커브는 최소 1개 포인트를 가져야 한다");
        }
        this.points = List.copyOf(pointsAscending);
    }

    /**
     * 발동 체결가에서 score를 보간한다.
     *
     * @param price 발동 체결가
     * @return 두 최근접 그리드 포인트 사이의 선형 보간값. 범위 밖이면 최외곽 포인트의 score로 클램프한다(외삽 없음)
     */
    public BigDecimal scoreAt(BigDecimal price) {
        PricePoint first = points.getFirst();
        if (price.compareTo(first.price()) <= 0) {
            return first.score();
        }
        PricePoint last = points.getLast();
        if (price.compareTo(last.price()) >= 0) {
            return last.score();
        }
        PricePoint lower = first;
        for (PricePoint point : points) {
            if (point.price().compareTo(price) > 0) {
                return interpolate(lower, point, price);
            }
            lower = point;
        }
        // 그리드가 오름차순이라면 도달하지 않는다 — 방어적 폴백
        return lower.score();
    }

    /** {@code S1 + (S2-S1) * (P-P1) / (P2-P1)} — 두 그리드 포인트 사이의 1차 선형 보간. */
    private static BigDecimal interpolate(PricePoint lower, PricePoint upper, BigDecimal price) {
        BigDecimal priceSpan = upper.price().subtract(lower.price());
        if (priceSpan.signum() == 0) {
            return lower.score();
        }
        BigDecimal scoreSpan = upper.score().subtract(lower.score());
        BigDecimal ratio =
                price.subtract(lower.price())
                        .divide(priceSpan, INTERPOLATION_SCALE, RoundingMode.HALF_UP);
        return lower.score().add(scoreSpan.multiply(ratio));
    }
}
