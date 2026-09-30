package com.aaa.notifier.telegram;

import com.aaa.notifier.telegram.TelegramMetrics.Channel;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * 메시지 1건의 발송 시도 반복 (design.md §3) — 요청 직전 safe_mode 확인 → 페이싱 → {@code sendMessage} → 결과 분류.
 *
 * <p><b>디스패처 스레드 전용이다</b>(직전 요청 시각을 동기화 없이 보관한다). 429 재발송 횟수·누적 대기와 일시 오류 재시도 횟수는 호출마다 0에서 시작하므로 한
 * 알림이 디스패처를 붙잡는 시간은 유한하다(REQ-022, plan.md §F 최악 점유 계산).
 */
@Slf4j
final class SendLoop {

    private final DeliveryContext context;
    private Instant lastRequestAt;

    SendLoop(DeliveryContext context) {
        this.context = context;
    }

    /**
     * 메시지 1건을 보낸다.
     *
     * @param text 본문
     * @param channel 알림/요약 구분 (계측용)
     * @return 최종 결과
     * @throws InterruptedException 대기 중 정상 종료 인터럽트
     */
    Attempt send(String text, Channel channel) throws InterruptedException {
        Budget budget = new Budget();
        while (true) {
            // ① 모든 요청 직전(최초·재시도·429 재발송)에 새로 읽는다 — 켜져 있으면 요청을 시작하지 않는다(REQ-031, D6)
            SafeModeState state = context.safeMode().read().state();
            if (state != SafeModeState.OFF) {
                return Attempt.blocked(state);
            }
            pace(context.policy().minSendInterval());
            SendOutcome outcome = context.api().sendMessage(text);
            context.metrics().sendResult(channel, outcome.kind());
            Optional<Attempt> terminal =
                    switch (outcome.kind()) {
                        case SUCCESS -> Optional.of(Attempt.sent(outcome.messageId()));
                        case PERMANENT ->
                                Optional.of(Attempt.of(Attempt.Result.PERMANENT, outcome.detail()));
                        case RATE_LIMITED -> waitForRateLimit(outcome, budget);
                        case TRANSIENT -> backOff(outcome, budget);
                    };
            if (terminal.isPresent()) {
                return terminal.get();
            }
        }
    }

    /**
     * 429 — 이번 대기를 더한 누적 대기나 다음 재발송 횟수가 상한을 넘게 되면 기다리지 않고 끝낸다(REQ-022). 아니면 기다린 뒤 다시 시도한다.
     *
     * @return 끝났으면 결과, 다시 시도하면 빈 값
     */
    private Optional<Attempt> waitForRateLimit(SendOutcome outcome, Budget budget)
            throws InterruptedException {
        DeliveryPolicy policy = context.policy();
        Duration wait =
                outcome.retryAfter() == null ? policy.rateLimitDefaultWait() : outcome.retryAfter();
        boolean resendCapExceeded = budget.resends + 1 > policy.rateLimitMaxResends();
        boolean waitCapExceeded =
                budget.waited.plus(wait).compareTo(policy.rateLimitMaxCumulativeWait()) > 0;
        if (resendCapExceeded || waitCapExceeded) {
            return Optional.of(Attempt.of(Attempt.Result.RATE_LIMIT_CAP, outcome.detail()));
        }
        context.sleeper().sleep(wait);
        budget.waited = budget.waited.plus(wait);
        budget.resends++;
        return Optional.empty();
    }

    /**
     * 5xx·타임아웃·입출력 오류 — 재시도가 남아 있으면 지수 백오프 뒤 다시 시도한다(REQ-023).
     *
     * @return 소진했으면 결과, 다시 시도하면 빈 값
     */
    private Optional<Attempt> backOff(SendOutcome outcome, Budget budget)
            throws InterruptedException {
        DeliveryPolicy policy = context.policy();
        if (budget.retries >= policy.transientRetries()) {
            return Optional.of(Attempt.of(Attempt.Result.TRANSIENT_EXHAUSTED, outcome.detail()));
        }
        context.sleeper().sleep(backoff(policy, budget.retries));
        budget.retries++;
        return Optional.empty();
    }

    /** 같은 chat_id 연속 요청 사이 최소 간격(채팅당 1건/초)을 지킨다 — 429 재발송도 이 간격을 거친다. */
    private void pace(Duration minInterval) throws InterruptedException {
        if (lastRequestAt != null) {
            Duration elapsed = Duration.between(lastRequestAt, context.clock().instant());
            if (elapsed.compareTo(minInterval) < 0) {
                context.sleeper().sleep(minInterval.minus(elapsed));
            }
        }
        lastRequestAt = context.clock().instant();
    }

    private static Duration backoff(DeliveryPolicy policy, int retry) {
        long factor = (long) Math.pow(policy.backoffMultiplier(), retry);
        return policy.backoffInitial().multipliedBy(factor);
    }

    /** 알림 1건의 재시도 예산 — 알림마다 새로 시작한다. 429 재발송과 일시 오류 재시도는 따로 센다. */
    private static final class Budget {
        private int resends;
        private int retries;
        private Duration waited = Duration.ZERO;
    }

    /**
     * 발송 시도의 최종 결과.
     *
     * @param result 분류
     * @param messageId 성공 시 {@code message_id}
     * @param blockedBy {@link Result#SAFE_MODE}일 때 요청 직전에 관측한 상태
     * @param detail 로그용 설명 (토큰 마스킹됨)
     */
    record Attempt(Result result, Long messageId, SafeModeState blockedBy, String detail) {

        static Attempt sent(Long messageId) {
            return new Attempt(Result.SENT, messageId, null, "ok");
        }

        static Attempt blocked(SafeModeState state) {
            return new Attempt(Result.SAFE_MODE, null, state, "safe_mode=" + state);
        }

        static Attempt of(Result result, String detail) {
            return new Attempt(result, null, null, detail);
        }

        /** 결과 분류. */
        enum Result {
            SENT,
            SAFE_MODE,
            RATE_LIMIT_CAP,
            TRANSIENT_EXHAUSTED,
            PERMANENT
        }
    }
}
