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

    @ParameterizedTest(name = "※({0} → {1})는 Tier 1이면서 유지 시간 {3}·{2} 유형의 쿨다운을 쓴다")
    @CsvSource({
        "HOLD,        STRONG_BUY,  HOLD_ENTRY, PT15M",
        "HOLD,        STRONG_SELL, HOLD_ENTRY, PT15M",
        "BUY,         STRONG_SELL, DIRECT,     PT5M",
        "SELL,        STRONG_BUY,  DIRECT,     PT5M",
        "STRONG_BUY,  STRONG_SELL, DIRECT,     PT5M",
        "STRONG_SELL, STRONG_BUY,  DIRECT,     PT5M",
    })
    @DisplayName(
            "REQ-042 / FILTER-002 REQ-008 — 직접 전환 ※칸은 직접 전환 유지 시간, HOLD 진입 ※칸은 전용 유지 시간(15분)을 쓰고 쿨다운은 동시 성립 유형 값을 쓴다")
    void starCells_reuseTransitionTypeParameters(
            Grade from, Grade to, TransitionType type, String dwell) {
        TransitionClass cell = TransitionMatrix.classify(from, to).orElseThrow();
        TransitionPolicy policy =
                new TransitionPolicy(FilterPipelines.DWELL, FilterPipelines.COOLDOWN);

        assertThat(cell.tier()).isEqualTo(1);
        assertThat(cell.type()).isEqualTo(type);
        assertThat(policy.dwellOf(cell)).hasToString(dwell);
        assertThat(policy.cooldownOf(cell))
                .isEqualTo(
                        policy.cooldownOf(
                                new TransitionClass(2, type, TransitionClass.Kind.TYPED)));
    }

    @ParameterizedTest(name = "{0} → {1}는 유지 시간 {2}·쿨다운 {3}을 쓴다")
    @CsvSource({
        "BUY,         STRONG_BUY,  PT10M, PT20M",
        "SELL,        STRONG_SELL, PT10M, PT20M",
        "STRONG_BUY,  BUY,         PT7M,  PT10M",
        "STRONG_SELL, SELL,        PT7M,  PT10M",
    })
    @DisplayName(
            "REQ-035 / FILTER-002 REQ-008 — 순수 강화(Tier1 반복 쿨다운)·완화(Tier3 쿨다운)도 유지 시간을 채워야 발송 후보다 (\"즉시\" 대체)")
    void untypedCells_requireDwell(Grade from, Grade to, String dwell, String cooldown) {
        TransitionClass cell = TransitionMatrix.classify(from, to).orElseThrow();
        TransitionPolicy policy =
                new TransitionPolicy(FilterPipelines.DWELL, FilterPipelines.COOLDOWN);

        assertThat(policy.dwellOf(cell)).hasToString(dwell);
        assertThat(policy.cooldownOf(cell)).hasToString(cooldown);
    }

    @ParameterizedTest(name = "{0} → {1} = 유지 시간 {2}")
    @CsvSource({
        // ① 직접 전환 8칸(※칸 4칸 포함) 5m
        "STRONG_BUY,  SELL,        PT5M",
        "STRONG_BUY,  STRONG_SELL, PT5M",
        "BUY,         SELL,        PT5M",
        "BUY,         STRONG_SELL, PT5M",
        "SELL,        STRONG_BUY,  PT5M",
        "SELL,        BUY,         PT5M",
        "STRONG_SELL, STRONG_BUY,  PT5M",
        "STRONG_SELL, BUY,         PT5M",
        // ② HOLD 이탈 4칸 7m
        "STRONG_BUY,  HOLD,        PT7M",
        "BUY,         HOLD,        PT7M",
        "SELL,        HOLD,        PT7M",
        "STRONG_SELL, HOLD,        PT7M",
        // ③ 강도 완화 2칸 7m
        "STRONG_BUY,  BUY,         PT7M",
        "STRONG_SELL, SELL,        PT7M",
        // ④ HOLD 진입·비STRONG 2칸 10m
        "HOLD,        BUY,         PT10M",
        "HOLD,        SELL,        PT10M",
        // ⑤ 순수 강도 강화 2칸 10m
        "BUY,         STRONG_BUY,  PT10M",
        "SELL,        STRONG_SELL, PT10M",
        // ⑥ HOLD 진입·STRONG 2칸 15m
        "HOLD,        STRONG_BUY,  PT15M",
        "HOLD,        STRONG_SELL, PT15M",
    })
    @DisplayName("FILTER-002 AC-07 ① — 비대각 20칸이 6개 유지 시간 유형에 design.md §3.2 표대로 대응한다")
    void everyCell_mapsToDesignDwell(Grade from, Grade to, String dwell) {
        TransitionPolicy policy =
                new TransitionPolicy(FilterPipelines.DWELL, FilterPipelines.COOLDOWN);

        assertThat(policy.dwellOf(TransitionMatrix.classify(from, to).orElseThrow()))
                .hasToString(dwell);
    }
}
