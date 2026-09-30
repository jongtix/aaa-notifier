package com.aaa.notifier.telegram;

import java.time.Duration;

/**
 * {@code sendMessage} 응답 1건의 분류 결과 (설계 초안 [D-13] 4분류, REQ-021~024).
 *
 * @param kind 분류
 * @param messageId 성공 시 {@code result.message_id} (해석 불가면 {@code null})
 * @param retryAfter 429의 {@code parameters.retry_after} (없으면 {@code null} — 호출 쪽이 기본 대기 적용)
 * @param detail 로그용 설명 — 토큰이 마스킹된 문자열만 담는다(REQ-051)
 */
public record SendOutcome(Kind kind, Long messageId, Duration retryAfter, String detail) {

    static SendOutcome success(Long messageId) {
        return new SendOutcome(Kind.SUCCESS, messageId, null, "ok");
    }

    static SendOutcome rateLimited(Duration retryAfter, String detail) {
        return new SendOutcome(Kind.RATE_LIMITED, null, retryAfter, detail);
    }

    static SendOutcome transientFailure(String detail) {
        return new SendOutcome(Kind.TRANSIENT, null, null, detail);
    }

    static SendOutcome permanentFailure(String detail) {
        return new SendOutcome(Kind.PERMANENT, null, null, detail);
    }

    /** 응답 분류. */
    public enum Kind {
        /** HTTP 200 + {@code ok:true}. */
        SUCCESS,
        /** HTTP 429 — 대기 후 재발송, 연속 실패 카운터 비반영. */
        RATE_LIMITED,
        /** 5xx·연결/응답 타임아웃·입출력 오류 — 백오프 재시도. */
        TRANSIENT,
        /** 429 외 4xx — 재시도·큐잉 없음. */
        PERMANENT
    }
}
