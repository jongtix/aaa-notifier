package com.aaa.notifier.telegram;

import com.aaa.notifier.filter.AlertDecision;
import java.time.OffsetDateTime;

/** {@code stream:alert} 발행 포트 (REQ-041) — 개별 알림 {@code SENT}에만 쓴다. 구현은 실패를 전파하지 않는다(fail-open). */
@FunctionalInterface
public interface AlertPublisher {

    /**
     * @param decision 발송된 결정
     * @param telegramMessageId 응답의 {@code message_id} ({@code null} 가능)
     * @param sentAt 발송 성립 시각 (KST)
     */
    void publish(AlertDecision decision, Long telegramMessageId, OffsetDateTime sentAt);
}
