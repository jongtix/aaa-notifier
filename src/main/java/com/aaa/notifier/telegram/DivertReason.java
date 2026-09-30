package com.aaa.notifier.telegram;

/** 알림이 개별 발송 대신 이관 지점(design.md §4)으로 간 사유 — 대기 큐 항목의 {@code reason}에 소문자로 남는다. */
public enum DivertReason {
    /** 요청 직전 확인에서 safe_mode가 켜져 있었다. */
    SAFE_MODE,
    /** 429 재발송 횟수 또는 누적 대기 상한 초과 (REQ-022). */
    RATE_LIMIT_CAP,
    /** 일시 오류 재시도 소진 (REQ-023). */
    TRANSIENT_EXHAUSTED,
    /** 디스패치 버퍼 가득 참 (plan.md §D.2). */
    BUFFER_FULL,
    /** 정상 종료 시 미발송 잔량 (REQ-004). */
    SHUTDOWN
}
