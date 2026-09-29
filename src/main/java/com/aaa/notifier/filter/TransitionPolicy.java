package com.aaa.notifier.filter;

import java.time.Duration;

/**
 * 전환 칸별 확증 횟수·쿨다운 선택 (REQ-032/034/035/042) — 수치는 전부 {@code application.yml}에서 온다.
 *
 * @param confirm 전환 유형별 확증 횟수
 * @param cooldown 전환 유형별·Tier별 쿨다운
 */
public record TransitionPolicy(
        FilterProperties.Confirm confirm, FilterProperties.Cooldown cooldown) {

    /** 칸의 확증 횟수. 무확증 칸은 1(감지 즉시). */
    public int confirmations(TransitionClass cell) {
        if (!cell.requiresConfirmation()) {
            return 1;
        }
        return switch (cell.type()) {
            case DIRECT -> confirm.direct();
            case HOLD_EXIT -> confirm.holdExit();
            case HOLD_ENTRY -> confirm.holdEntry();
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
