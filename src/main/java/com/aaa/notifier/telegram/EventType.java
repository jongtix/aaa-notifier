package com.aaa.notifier.telegram;

/**
 * 실발송 경로가 {@code notification_log}에 남기는 사건 종류 (V49 COMMENT 허용값). {@code DRYRUN}은 FILTER-001 싱크가
 * 기록하므로 여기에 없다.
 */
public enum EventType {
    /** 발송 성공. */
    SENT,
    /** 영구 오류(4xx)로 발송 실패. */
    SEND_FAILED,
    /** 대기 큐에 적재. */
    QUEUED,
    /** 대기 큐 요약 발송 성공. */
    SUMMARY_SENT
}
