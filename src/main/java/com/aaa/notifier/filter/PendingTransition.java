package com.aaa.notifier.filter;

import java.time.Instant;
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
 * @param since 전환 체결 시각 — 같은 등급 연속 구간의 첫 체결 시각(SPEC-NOTIFIER-FILTER-002 REQ-011). 배포 전 형식에서 읽은 후보는
 *     {@code null}이며, 파이프라인이 처음 평가하는 체결의 체결 시각으로 채운다
 */
public record PendingTransition(
        Grade from, Grade to, int count, SuppressionReason lastReason, Instant since) {

    public PendingTransition {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
    }

    /**
     * 유효 등급 전환 직후의 새 후보.
     *
     * @param since 전환 체결(교차한 체결)의 체결 시각
     */
    public static PendingTransition started(Grade from, Grade to, Instant since) {
        return new PendingTransition(from, to, 0, null, since);
    }

    /** 억제 사유 표식만 바꾼 사본. */
    public PendingTransition withReason(SuppressionReason reason) {
        return new PendingTransition(from, to, count, reason, since);
    }

    /** 감지 1회를 더한 사본. */
    public PendingTransition counted() {
        return new PendingTransition(from, to, count + 1, lastReason, since);
    }

    /** 전환 체결 시각만 채운 사본 — 배포 전 형식 후보의 첫 평가에서 쓴다(REQ-011 후단). */
    public PendingTransition withSince(Instant tradeTime) {
        return new PendingTransition(from, to, count, lastReason, tradeTime);
    }
}
