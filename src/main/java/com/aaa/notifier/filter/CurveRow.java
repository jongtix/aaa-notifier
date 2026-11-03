package com.aaa.notifier.filter;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code signal_price_curves} 1행 (SPEC-SIGNAL-PRICECURVE-001 REQ-006) — {@code curve} JSON 컬럼을 가격
 * 오름차순 {@link PricePoint} 목록으로 역직렬화한 상태. 적재기({@link ReferenceDataLoader})와 조립기({@link
 * ReferenceAssembler})의 경계라는 점은 {@link ReferenceRows}의 다른 행 타입과 같다 — {@code points}가 가변 {@link
 * List}를 방어적으로 복사해야 해서 별도 top-level 타입으로 둔다({@code ReferenceRows}는 package-private 인터페이스라 그 안에
 * nested record의 compact 생성자를 명시적으로 {@code public}으로 적어야 하고, 그러면 PMD {@code
 * PublicMemberInNonPublicType}과 충돌한다).
 */
record CurveRow(long stockId, LocalDate tradeDate, String horizon, List<PricePoint> points) {

    CurveRow {
        points = List.copyOf(points);
    }
}
