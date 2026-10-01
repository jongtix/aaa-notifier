package com.aaa.notifier.filter;

import java.time.Duration;

/**
 * 전환 칸별 유지 시간·쿨다운 선택 (REQ-034/035/042, SPEC-NOTIFIER-FILTER-002 REQ-008) — 수치는 전부 {@code
 * application.yml}에서 온다.
 *
 * @param dwell 유지 시간 유형별 유지 시간
 * @param cooldown 전환 유형별·Tier별 쿨다운
 */
public record TransitionPolicy(FilterProperties.Dwell dwell, FilterProperties.Cooldown cooldown) {

    /**
     * 칸의 유지 시간 (SPEC-NOTIFIER-FILTER-002 REQ-008, design.md §3) — 20칸이 6개 유지 시간 유형 중 정확히 하나에 대응한다.
     *
     * <p>직접 전환 ※칸(STRONG 도착)은 FILTER-001 REQ-042의 동시 성립 규칙대로 직접 전환 값을 쓰고, HOLD 진입은 {@code tier}로
     * STRONG 도착(⑥)과 비STRONG 도착(④)을 가른다.
     */
    public Duration dwellOf(TransitionClass cell) {
        return switch (cell.kind()) {
            case STRENGTHEN -> dwell.strengthUp();
            case WEAKEN -> dwell.weakening();
            case TYPED ->
                    switch (cell.type()) {
                        case DIRECT -> dwell.direct();
                        case HOLD_EXIT -> dwell.holdExit();
                        case HOLD_ENTRY ->
                                cell.tier() == 1 ? dwell.strongEntry() : dwell.holdEntry();
                    };
        };
    }

    /** 칸의 쿨다운. 0이면 쿨다운 없음. */
    public Duration cooldownOf(TransitionClass cell) {
        return switch (cell.kind()) {
            case STRENGTHEN -> cooldown.tier1Repeat();
            case WEAKEN -> cooldown.tier3Weakening();
            case TYPED ->
                    switch (cell.type()) {
                        case DIRECT -> cooldown.direct();
                        case HOLD_EXIT -> cooldown.holdExit();
                        case HOLD_ENTRY -> cooldown.holdEntry();
                    };
        };
    }
}
