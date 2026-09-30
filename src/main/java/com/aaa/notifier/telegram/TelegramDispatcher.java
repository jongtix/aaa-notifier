package com.aaa.notifier.telegram;

import com.aaa.notifier.common.logging.SafeMdc;
import com.aaa.notifier.common.logging.TraceIdManager;
import com.aaa.notifier.filter.AlertDecision;
import com.aaa.notifier.filter.MarketSession;
import com.aaa.notifier.telegram.SafeModeStore.AutoEntry;
import com.aaa.notifier.telegram.SendLoop.Attempt;
import com.aaa.notifier.telegram.TelegramMetrics.Channel;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessException;

/**
 * 결정 수신과 텔레그램 왕복을 분리하는 단일 디스패처 (REQ-004, plan.md §D.2, design.md §1·§3).
 *
 * <p>틱 소비 스레드는 발송 후보를 제한 크기 버퍼에 넣고 즉시 돌아간다([F-7]). 가상 스레드 하나가 버퍼를 순서대로 꺼내 페이싱·재시도·429
 * 대기·기록·발행·safe_mode 자동 진입·대기 큐 요약까지 전부 수행한다 — 발송 순서와 채팅당 1건/초를 한 지점에서 보장하고, 요약과 신규 발송이 경합하지 않게 한다.
 * 연속 실패 카운터는 이 스레드만 만진다(research.md §4.3 ⑤).
 *
 * <p><b>종료 순서</b>: 소비 계층({@code SmartLifecycle} 기본 phase {@code Integer.MAX_VALUE})보다 한 단계 낮은
 * phase라 소비가 먼저 멈춘 뒤 멈춘다. 멈출 때 버퍼 잔량은 종료 시 1회 읽은 safe_mode로 이관 지점을 지나고(REQ-004), 그 뒤 도착한 결정은 버퍼에 두지
 * 않고 곧바로 이관한다.
 */
@Slf4j
public class TelegramDispatcher implements SmartLifecycle {

    /** 소비 계층보다 늦게 시작·먼저 멈추는 쪽이 아니라 — 소비 계층이 먼저 멈춘 다음 멈추도록 한 단계 낮춘다. */
    private static final int PHASE = Integer.MAX_VALUE - 1;

    /** 버퍼 대기 상한 — 요약 요청을 이 간격 안에 집어 든다. */
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(1);

    /** 종료 시 디스패처 정리 대기 상한 — {@code timeout-per-shutdown-phase}(30s)보다 짧다. */
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(15);

    private final DeliveryContext context;
    private final BlockingQueue<Candidate> buffer;
    private final SendLoop sendLoop;
    private final QueueTransfer transfer;
    private final QueueSummarizer summarizer;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean summaryPending = new AtomicBoolean();
    private final AtomicInteger failureCount = new AtomicInteger();
    private Thread worker;

    /**
     * @param context 협력자 묶음
     * @param bufferCapacity 디스패치 버퍼 상한 — 초과분은 즉시 이관한다
     */
    public TelegramDispatcher(DeliveryContext context, int bufferCapacity) {
        this.context = context;
        this.buffer = new ArrayBlockingQueue<>(bufferCapacity);
        this.sendLoop = new SendLoop(context);
        this.transfer = new QueueTransfer(context);
        this.summarizer = new QueueSummarizer(context, sendLoop);
    }

    /**
     * 발송 후보를 버퍼에 넣고 즉시 돌아간다 — 틱 소비 스레드에서 호출된다.
     *
     * <p>버퍼가 가득 찼거나 이미 멈췄으면 이관 지점으로 보낸다. 이때 safe_mode는 Redis를 읽지 않고 마지막 관측값을 쓴다 — 버퍼가 가득 찼다는 것은
     * 디스패처가 발송 중이라는 뜻이므로 그 값은 요청 1건 이내로 새롭다(design.md §4.2).
     *
     * @param decision 발송 후보
     */
    // @MX:ANCHOR: [AUTO] 틱 소비 스레드 → 디스패처 핸드오프 — 이 메서드는 텔레그램 응답 시간과 무관하게 반환해야 한다
    // @MX:REASON: 여기서 HTTP 왕복·대기를 하면 틱 처리 전체가 멈춘다([F-7], REQ-004); 호출처
    // TelegramAlertDecisionSink·테스트 다수
    public void submit(AlertDecision decision) {
        boolean shuttingDown = stopped.get();
        if (!shuttingDown && buffer.offer(new Candidate(decision, context.clock().instant()))) {
            return;
        }
        transfer.divert(
                decision,
                shuttingDown ? DivertReason.SHUTDOWN : DivertReason.BUFFER_FULL,
                context.safeMode().lastObserved());
    }

    /** 대기 큐 요약을 요청한다 — 디스패처가 다음 차례에 처리한다. */
    public void requestSummary() {
        summaryPending.set(true);
    }

    /**
     * 버퍼에서 후보 1건을 꺼내 처리한다.
     *
     * @return 처리한 후보가 있었는지
     */
    boolean processNext() {
        Candidate candidate = buffer.poll();
        if (candidate == null) {
            return false;
        }
        deliver(candidate);
        return true;
    }

    /** 요약 요청이 있으면 처리한다. */
    void processPendingSummary() {
        if (!summaryPending.getAndSet(false)) {
            return;
        }
        try {
            summarizer.summarize();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 멈춘 뒤 버퍼 잔량을 종료 시 1회 읽은 safe_mode로 이관한다 (REQ-004, design.md §4.2). */
    void drainOnShutdown() {
        stopped.set(true);
        SafeModeState state = context.safeMode().read().state();
        Candidate candidate = buffer.poll();
        while (candidate != null) {
            transfer.divert(candidate.decision(), DivertReason.SHUTDOWN, state);
            candidate = buffer.poll();
        }
    }

    int consecutiveFailures() {
        return failureCount.get();
    }

    boolean summaryRequested() {
        return summaryPending.get();
    }

    /** 알림 1건 발송 흐름 (design.md §3). */
    private void deliver(Candidate candidate) {
        AlertDecision decision = candidate.decision();
        // TraceIdManager.set()은 UUID v4가 아니면 새 값을 만든다 — 결정의 trace_id(collector 발급)를 그대로 잇기 위해 직접
        // 넣는다
        SafeMdc.put(TraceIdManager.MDC_KEY_TRACE_ID, decision.traceId());
        try {
            String text = context.formatter().formatAlert(decision);
            Attempt attempt;
            try {
                attempt = sendLoop.send(text, Channel.ALERT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                transfer.divert(decision, DivertReason.SHUTDOWN, context.safeMode().read().state());
                return;
            }
            switch (attempt.result()) {
                case SENT -> onSent(candidate, text, attempt.messageId());
                case SAFE_MODE ->
                        transfer.divert(decision, DivertReason.SAFE_MODE, attempt.blockedBy());
                case RATE_LIMIT_CAP ->
                        transfer.divert(decision, DivertReason.RATE_LIMIT_CAP, freshState());
                case TRANSIENT_EXHAUSTED -> onTransientExhausted(decision);
                case PERMANENT -> onPermanent(decision, text, attempt.detail());
            }
        } finally {
            TraceIdManager.clear();
        }
    }

    private void onSent(Candidate candidate, String text, Long messageId) {
        AlertDecision decision = candidate.decision();
        context.notificationLog().alert(EventType.SENT, decision, text, messageId);
        failureCount.set(0);
        context.alertPublisher()
                .publish(
                        decision,
                        messageId,
                        OffsetDateTime.now(context.clock().withZone(MarketSession.KST)));
        context.metrics()
                .latency(Duration.between(candidate.receivedAt(), context.clock().instant()));
        context.filterMetrics().decision(decision);
        log.info(
                "[telegram-dispatch] 발송 성공 trace_id={} message_id={}",
                decision.traceId(),
                messageId);
        if (queueNotEmpty()) {
            requestSummary();
        }
    }

    private void onTransientExhausted(AlertDecision decision) {
        int failures = failureCount.incrementAndGet();
        if (failures >= context.policy().failureThreshold()) {
            AutoEntry entry = context.safeMode().enterAuto();
            if (entry == AutoEntry.FAILED) {
                log.warn(
                        "[telegram-dispatch] safe_mode 자동 진입 기록 실패 — 다음 소진 때 다시 시도 failures={}",
                        failures);
            } else {
                // 기록했거나(AUTO) 운영자 값이 있어 기록하지 않았거나 — 어느 쪽이든 카운터는 새로 센다(design.md §3)
                failureCount.set(0);
                log.warn("[telegram-dispatch] 연속 실패 {}건 — safe_mode 자동 진입 {}", failures, entry);
            }
        }
        transfer.divert(decision, DivertReason.TRANSIENT_EXHAUSTED, freshState());
    }

    private void onPermanent(AlertDecision decision, String text, String detail) {
        context.notificationLog().alert(EventType.SEND_FAILED, decision, text, null);
        context.filterMetrics().decision(decision);
        log.warn(
                "[telegram-dispatch] 영구 오류 — 재시도·큐잉 없음 trace_id={} detail={}",
                decision.traceId(),
                detail);
    }

    /** 이관 지점에서 새로 읽는다 — 마지막 시도 중 운영자가 켰을 수 있다(R-A). */
    private SafeModeState freshState() {
        return context.safeMode().read().state();
    }

    private boolean queueNotEmpty() {
        try {
            return context.queue().size() > 0;
        } catch (DataAccessException e) {
            return false;
        }
    }

    // @MX:WARN: [AUTO] 가상 스레드 1개가 버퍼를 무한 루프로 소비한다 — 인터럽트와 running 플래그로만 멈춘다
    // @MX:REASON: 루프가 예외로 끝나면 이후 발송 후보가 버퍼에 쌓이다 버퍼 초과로만 이관된다; 처리되지 않은 예외는 uncaughtExceptionHandler가
    // ERROR로 남긴다
    private void runLoop() {
        while (running.get()) {
            processPendingSummary();
            try {
                Candidate candidate = buffer.poll(POLL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                if (candidate != null) {
                    deliver(candidate);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        worker =
                Thread.ofVirtual()
                        .name("telegram-dispatcher")
                        .uncaughtExceptionHandler(
                                (thread, error) ->
                                        log.error(
                                                "[telegram-dispatch] 디스패처가 처리되지 않은 예외로 종료됐다",
                                                error))
                        .start(this::runLoop);
        log.info("[telegram-dispatch] 디스패처 기동");
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        worker.interrupt();
        try {
            worker.join(SHUTDOWN_WAIT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        drainOnShutdown();
        log.info("[telegram-dispatch] 디스패처 종료 — 버퍼 잔량 이관 완료");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    /**
     * 버퍼 항목.
     *
     * @param decision 발송 후보
     * @param receivedAt 결정 수신 시각 — 발송 지연 계측의 시작점
     */
    record Candidate(AlertDecision decision, Instant receivedAt) {}
}
