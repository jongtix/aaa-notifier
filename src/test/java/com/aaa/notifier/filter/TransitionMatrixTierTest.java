package com.aaa.notifier.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 5×5 전환 매트릭스 전수 대조 (REQ-041/042, AC-15) — spec.md REQ-041 표를 20칸 그대로 옮겨 적은 기대값이다.
 *
 * <p>구현은 방향 대칭 규칙으로 계산하므로({@link TransitionMatrix}), 이 표가 규칙과 스펙 표의 일치를 보증하는 유일한 대조면이다. 표기: {@code
 * NONE} = 3종 유형에 속하지 않는 칸(순수 강화 {@code 1}, 완화 {@code 3}).
 */
@DisplayName("TransitionMatrix — 5×5 매트릭스 20칸 전수 검증 (AC-15)")
class TransitionMatrixTierTest {

    @ParameterizedTest(name = "{0} → {1} = Tier {2} / {3}")
    @CsvSource({
        // STRONG_BUY 행: — | 3 | 2·이탈 | 2·직 | 1※(직)
        "STRONG_BUY, BUY,         3, NONE",
        "STRONG_BUY, HOLD,        2, HOLD_EXIT",
        "STRONG_BUY, SELL,        2, DIRECT",
        "STRONG_BUY, STRONG_SELL, 1, DIRECT",
        // BUY 행: 1 | — | 2·이탈 | 2·직 | 1※(직)
        "BUY, STRONG_BUY,  1, NONE",
        "BUY, HOLD,        2, HOLD_EXIT",
        "BUY, SELL,        2, DIRECT",
        "BUY, STRONG_SELL, 1, DIRECT",
        // HOLD 행: 1※(진) | 2·진 | — | 2·진 | 1※(진)
        "HOLD, STRONG_BUY,  1, HOLD_ENTRY",
        "HOLD, BUY,         2, HOLD_ENTRY",
        "HOLD, SELL,        2, HOLD_ENTRY",
        "HOLD, STRONG_SELL, 1, HOLD_ENTRY",
        // SELL 행: 1※(직) | 2·직 | 2·이탈 | — | 1
        "SELL, STRONG_BUY,  1, DIRECT",
        "SELL, BUY,         2, DIRECT",
        "SELL, HOLD,        2, HOLD_EXIT",
        "SELL, STRONG_SELL, 1, NONE",
        // STRONG_SELL 행: 1※(직) | 2·직 | 2·이탈 | 3 | —
        "STRONG_SELL, STRONG_BUY, 1, DIRECT",
        "STRONG_SELL, BUY,        2, DIRECT",
        "STRONG_SELL, HOLD,       2, HOLD_EXIT",
        "STRONG_SELL, SELL,       3, NONE",
    })
    void cellMatchesSpecTable(Grade from, Grade to, int tier, String type) {
        TransitionClass cell = TransitionMatrix.classify(from, to).orElseThrow();

        assertThat(cell.tier()).isEqualTo(tier);
        assertThat(cell.type() == null ? "NONE" : cell.type().name()).isEqualTo(type);
    }

    @ParameterizedTest(name = "※({0} → {1})는 Tier 1이면서 {2} 유형의 확증 횟수를 쓴다")
    @CsvSource({
        "HOLD,        STRONG_BUY,  HOLD_ENTRY, 5",
        "HOLD,        STRONG_SELL, HOLD_ENTRY, 5",
        "BUY,         STRONG_SELL, DIRECT,     2",
        "SELL,        STRONG_BUY,  DIRECT,     2",
        "STRONG_BUY,  STRONG_SELL, DIRECT,     2",
        "STRONG_SELL, STRONG_BUY,  DIRECT,     2",
    })
    @DisplayName("REQ-042 — ※칸은 Tier 1 전용 세트 없이 동시 성립 유형의 확증·쿨다운을 쓴다")
    void starCells_reuseTransitionTypeParameters(
            Grade from, Grade to, TransitionType type, int confirmations) {
        TransitionClass cell = TransitionMatrix.classify(from, to).orElseThrow();
        TransitionPolicy policy =
                new TransitionPolicy(FilterPipelines.CONFIRM, FilterPipelines.COOLDOWN);

        assertThat(cell.tier()).isEqualTo(1);
        assertThat(cell.type()).isEqualTo(type);
        assertThat(policy.confirmations(cell)).isEqualTo(confirmations);
        assertThat(policy.cooldownOf(cell))
                .isEqualTo(
                        policy.cooldownOf(
                                new TransitionClass(2, type, TransitionClass.Kind.TYPED)));
    }

    @ParameterizedTest(name = "{0} → {1}는 무확증이며 {2} 쿨다운을 쓴다")
    @CsvSource({
        "BUY,         STRONG_BUY,  PT20M",
        "SELL,        STRONG_SELL, PT20M",
        "STRONG_BUY,  BUY,         PT10M",
        "STRONG_SELL, SELL,        PT10M",
    })
    @DisplayName("REQ-035 — 순수 강화(Tier1 반복 쿨다운)·완화(Tier3 쿨다운)는 확증 없이 즉시 발송 후보다")
    void untypedCells_skipConfirmation(Grade from, Grade to, String cooldown) {
        TransitionClass cell = TransitionMatrix.classify(from, to).orElseThrow();
        TransitionPolicy policy =
                new TransitionPolicy(FilterPipelines.CONFIRM, FilterPipelines.COOLDOWN);

        assertThat(cell.requiresConfirmation()).isFalse();
        assertThat(policy.confirmations(cell)).isEqualTo(1);
        assertThat(policy.cooldownOf(cell)).hasToString(cooldown);
    }
}
