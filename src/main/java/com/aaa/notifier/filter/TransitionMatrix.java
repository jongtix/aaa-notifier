package com.aaa.notifier.filter;

import java.util.Optional;

/**
 * 5×5 유효 등급 전환 매트릭스 (REQ-031/035/041/042, spec.md REQ-041 표).
 *
 * <p>표를 25칸 상수로 옮겨 적지 않고 <b>방향 대칭 규칙</b>으로 계산한다 — 매수·매도를 다른 파라미터로 나누는 비대칭 구현을 구조적으로 막기 위해서다 (설계 초안
 * [D-11]③). 규칙:
 *
 * <ul>
 *   <li>→ HOLD: HOLD 이탈, Tier 2
 *   <li>HOLD →: HOLD 진입, 도착이 STRONG이면 Tier 1(※) 아니면 Tier 2
 *   <li>매수계 ↔ 매도계: 직접 전환, 도착이 STRONG이면 Tier 1(※) 아니면 Tier 2
 *   <li>같은 방향 안에서 STRONG으로: 순수 강도 강화, Tier 1(무확증)
 *   <li>같은 방향 안에서 STRONG에서: 강도 완화, Tier 3(무확증)
 * </ul>
 *
 * <p>※칸은 Tier 1 전용 확증·쿨다운 세트를 따로 두지 않고 동시 성립한 전환 유형의 값을 그대로 쓴다(REQ-042). 25칸 전수 대조는 테스트가 spec 표와
 * 1:1로 검증한다.
 */
public final class TransitionMatrix {

    private TransitionMatrix() {}

    /**
     * 전환 전·후 유효 등급으로 칸을 분류한다.
     *
     * @return 대각(변화 없음)이면 빈 값
     */
    public static Optional<TransitionClass> classify(Grade from, Grade to) {
        if (from == to) {
            return Optional.empty();
        }
        int strongTier = isStrong(to) ? 1 : 2;
        if (to == Grade.HOLD) {
            return typed(2, TransitionType.HOLD_EXIT);
        }
        if (from == Grade.HOLD) {
            return typed(strongTier, TransitionType.HOLD_ENTRY);
        }
        if (isBuySide(from) != isBuySide(to)) {
            return typed(strongTier, TransitionType.DIRECT);
        }
        return Optional.of(
                isStrong(to)
                        ? new TransitionClass(1, null, TransitionClass.Kind.STRENGTHEN)
                        : new TransitionClass(3, null, TransitionClass.Kind.WEAKEN));
    }

    private static Optional<TransitionClass> typed(int tier, TransitionType type) {
        return Optional.of(new TransitionClass(tier, type, TransitionClass.Kind.TYPED));
    }

    private static boolean isStrong(Grade grade) {
        return grade == Grade.STRONG_BUY || grade == Grade.STRONG_SELL;
    }

    private static boolean isBuySide(Grade grade) {
        return grade == Grade.STRONG_BUY || grade == Grade.BUY;
    }
}
