package com.aaa.notifier.telegram;

import com.aaa.notifier.filter.AlertDecision;

/**
 * {@code notification_log} 발송 사건 기록 포트 — INSERT-ONLY 이벤트 소싱([D-5]). 구현은 실패를 전파하지 않는다(REQ-002 기록
 * fail-open).
 */
public interface NotificationLog {

    /**
     * 개별 알림 사건 1행 ({@code notification_type='TIMING'}, 결정의 {@code trace_id}).
     *
     * @param type 사건 종류
     * @param decision 결정
     * @param messageText 실제 보낸(또는 보내려던) 본문
     * @param telegramMessageId 응답의 {@code message_id} ({@code SENT} 외에는 {@code null})
     */
    void alert(EventType type, AlertDecision decision, String messageText, Long telegramMessageId);

    /**
     * 대기 큐 요약 사건 1행 ({@code notification_type='QUEUE_SUMMARY'}, 종목 무관 컬럼 NULL, 요약 시도마다 새 {@code
     * trace_id}).
     *
     * @param type {@code SUMMARY_SENT} 또는 {@code SEND_FAILED}
     * @param traceId 요약 시도의 새 추적 ID
     * @param messageText 실제 보낸(또는 보내려던) 요약 본문
     * @param telegramMessageId 응답의 {@code message_id} ({@code null} 가능)
     */
    void summary(EventType type, String traceId, String messageText, Long telegramMessageId);
}
