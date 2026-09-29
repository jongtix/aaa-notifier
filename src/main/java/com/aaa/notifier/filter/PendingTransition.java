package com.aaa.notifier.filter;

import java.util.Objects;

/**
 * {@code filter:confirm:{종목코드}:{horizon}} — 확증 대기 중인 전환 후보 (REQ-033, design.md §2).
 *
 * <p>유효 등급이 바뀌는 순간 새 후보로 <b>교체</b>된다 — 확증 대기 중 등급이 다시 바뀌면 이전 후보의 카운터는 리셋이다.
 *
 * @param from 전환 전 유효 등급
 * @param to 전환 후 유효 등급(= 현재 유효 등급)
 * @param count 가드를 통과한 연속 감지 횟수
 * @param lastReason 이 후보에 대해 마지막으로 방출한 억제 사유 — 같은 사유의 결정을 틱마다 반복 방출하지 않기 위한 표식
 */
public record PendingTransition(Grade from, Grade to, int count, SuppressionReason lastReason) {

    public PendingTransition {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
    }

    /** 유효 등급 전환 직후의 새 후보. */
    public static PendingTransition started(Grade from, Grade to) {
        return new PendingTransition(from, to, 0, null);
    }

    /** 억제 사유 표식만 바꾼 사본. */
    public PendingTransition withReason(SuppressionReason reason) {
        return new PendingTransition(from, to, count, reason);
    }

    /** 감지 1회를 더한 사본. */
    public PendingTransition counted() {
        return new PendingTransition(from, to, count + 1, lastReason);
    }
}
