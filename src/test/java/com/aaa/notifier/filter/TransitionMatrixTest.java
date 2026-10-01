package com.aaa.notifier.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TransitionMatrix — 전환 유형 방향 대칭 분류 (REQ-031, AC-10)")
class TransitionMatrixTest {

    @Test
    @DisplayName("AC-10 — BUY→SELL=DIRECT, SELL→HOLD=HOLD_EXIT, HOLD→BUY·HOLD→SELL=모두 HOLD_ENTRY")
    void classifiesThreeTypesSymmetrically() {
        assertThat(TransitionMatrix.classify(Grade.BUY, Grade.SELL).orElseThrow().type())
                .isEqualTo(TransitionType.DIRECT);
        assertThat(TransitionMatrix.classify(Grade.SELL, Grade.HOLD).orElseThrow().type())
                .isEqualTo(TransitionType.HOLD_EXIT);
        assertThat(TransitionMatrix.classify(Grade.HOLD, Grade.BUY).orElseThrow().type())
                .isEqualTo(TransitionType.HOLD_ENTRY);
        assertThat(TransitionMatrix.classify(Grade.HOLD, Grade.SELL).orElseThrow().type())
                .isEqualTo(TransitionType.HOLD_ENTRY);
    }

    @Test
    @DisplayName("대각(변화 없음)은 전환이 아니다")
    void diagonal_isNotATransition() {
        for (Grade grade : Grade.values()) {
            assertThat(TransitionMatrix.classify(grade, grade)).isEmpty();
        }
    }

    @Test
    @DisplayName(
            "순수 강도 강화(BUY→STRONG_BUY)는 무유형 Tier 1이며 순수 강도 강화 유지 시간 유형(10분)을 쓴다 (REQ-035, FILTER-002 REQ-008)")
    void pureStrengthening_isTierOneWithStrengthUpDwell() {
        TransitionClass cell = TransitionMatrix.classify(Grade.BUY, Grade.STRONG_BUY).orElseThrow();
        TransitionPolicy policy =
                new TransitionPolicy(FilterPipelines.DWELL, FilterPipelines.COOLDOWN);

        assertThat(cell.tier()).isEqualTo(1);
        assertThat(cell.type()).isNull();
        assertThat(policy.dwellOf(cell)).isEqualTo(FilterPipelines.DWELL.strengthUp());
        assertThat(cell.kind()).isEqualTo(TransitionClass.Kind.STRENGTHEN);
    }
}
