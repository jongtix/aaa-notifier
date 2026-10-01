package com.aaa.notifier.filter;

import java.time.Instant;
import java.util.Objects;

/**
 * {@code filter:confirm:{종목코드}:{horizon}} — 유지 확증 대기 중인 전환 후보 (SPEC-NOTIFIER-FILTER-002 design.md
 * §2).
 *
 * <p>확증은 체결 건수가 아니라 유지 시간이다 — 전환 체결 시각({@code since})부터 평가 중인 체결의 체결 시각까지가 그 칸의 유지 시간 이상이면
 * 통과한다(REQ-001/007). 가드 대기 중에도 {@code since}는 그대로다(REQ-009).
 *
 * @param from 전환 전 유효 등급
 * @param to 전환 후 유효 등급(= 현재 유효 등급)
 * @param since 전환 체결 시각 — 같은 등급 연속 구간의 첫 체결 시각(SPEC-NOTIFIER-FILTER-002 REQ-011). 배포 전 형식에서 읽은 후보는
 *     {@code null}이며, 파이프라인이 처음 평가하는 체결의 체결 시각으로 채운다
 */
public record PendingTransition(Grade from, Grade to, Instant since) {

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
        return new PendingTransition(from, to, since);
    }

    /** 전환 체결 시각만 채운 사본 — 배포 전 형식 후보의 첫 평가에서 쓴다(REQ-011 후단). */
    public PendingTransition withSince(Instant tradeTime) {
        return new PendingTransition(from, to, tradeTime);
    }
}
