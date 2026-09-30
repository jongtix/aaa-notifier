package com.aaa.notifier.telegram;

import com.aaa.notifier.telegram.SendOutcome.Kind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 매매봇 실발송 계측 (REQ-NOTIFIER-TELEGRAM-052) — 실발송 모드가 켜졌을 때만 생성된다.
 *
 * <p><b>모든 시리즈를 생성 시점에 등록한다</b> — 게이지는 물론 카운터·지연 분포도 첫 사건 전부터 값 0으로 존재해야 vmalert 룰
 * (NOTIFIER-OBSV-001)이 기동 직후부터 무장된다(collector #88 교훈). 게이지 이름에는 {@code _total}을 쓰지 않는다 — Prometheus
 * 명명 규칙이 카운터가 아닌 미터의 {@code _total}을 조용히 떼어 낸다.
 *
 * <p>태그 값은 고정 열거형 이름뿐이다 — 토큰·심볼 등 가변 값을 태그로 쓰지 않는다(REQ-051, 카디널리티).
 */
public final class TelegramMetrics {

    static final String SEND = "notifier.telegram.send";
    static final String SEND_LATENCY = "notifier.telegram.send.latency";
    static final String QUEUE_DEPTH = "notifier.telegram.queue.depth";
    static final String SAFE_MODE = "notifier.telegram.safe.mode";
    static final String RECORD_FAILURE = "notifier.telegram.record.failure";
    static final String PUBLISH_FAILURE = "notifier.telegram.alert.publish.failure";
    static final String QUEUE_WRITE_FAILURE = "notifier.telegram.queue.write.failure";
    static final String QUEUE_DISCARDED = "notifier.telegram.queue.discarded";

    private static final String CHANNEL = "channel";
    private static final String RESULT = "result";
    private static final String REASON = "reason";
    private static final String SOURCE = "source";

    private final MeterRegistry registry;
    private final AtomicLong observedQueueDepth = new AtomicLong();
    private final AtomicInteger autoActive = new AtomicInteger();
    private final AtomicInteger operatorActive = new AtomicInteger();

    private TelegramMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 계측기를 만들고 전 시리즈를 즉시 등록한다.
     *
     * @param registry Micrometer 레지스트리
     * @return 계측기
     */
    public static TelegramMetrics create(MeterRegistry registry) {
        TelegramMetrics metrics = new TelegramMetrics(registry);
        metrics.registerAll();
        return metrics;
    }

    private void registerAll() {
        for (Channel channel : Channel.values()) {
            for (Kind kind : Kind.values()) {
                sendCounter(channel, kind);
            }
        }
        for (DiscardReason reason : DiscardReason.values()) {
            discardCounter(reason);
        }
        latencyTimer();
        registry.counter(RECORD_FAILURE);
        registry.counter(PUBLISH_FAILURE);
        registry.counter(QUEUE_WRITE_FAILURE);
        Gauge.builder(QUEUE_DEPTH, observedQueueDepth, AtomicLong::get)
                .description("queue:telegram:pending 길이 (마지막 관측값)")
                .register(registry);
        Gauge.builder(SAFE_MODE, autoActive, AtomicInteger::get)
                .description("safe_mode 켜짐(1)/꺼짐(0) — 출처별")
                .tag(SOURCE, "auto")
                .register(registry);
        Gauge.builder(SAFE_MODE, operatorActive, AtomicInteger::get)
                .description("safe_mode 켜짐(1)/꺼짐(0) — 출처별")
                .tag(SOURCE, "operator")
                .register(registry);
    }

    /** sendMessage 응답 1건을 분류별로 센다 ({@code notifier_telegram_send_total{channel, result}}). */
    public void sendResult(Channel channel, Kind kind) {
        sendCounter(channel, kind).increment();
    }

    /** 결정 수신 → 200 응답 지연. */
    public void latency(Duration elapsed) {
        latencyTimer().record(elapsed);
    }

    /** 대기 큐 길이 관측값. */
    public void queueDepth(long depth) {
        observedQueueDepth.set(depth);
    }

    /** 가장 최근에 관측한 safe_mode 상태. */
    public void safeMode(SafeModeState state) {
        autoActive.set(state == SafeModeState.AUTO ? 1 : 0);
        operatorActive.set(state == SafeModeState.ON ? 1 : 0);
    }

    /** {@code notification_log} INSERT 실패 (fail-open). */
    public void recordFailure() {
        registry.counter(RECORD_FAILURE).increment();
    }

    /** {@code stream:alert} 발행 실패 (fail-open). */
    public void publishFailure() {
        registry.counter(PUBLISH_FAILURE).increment();
    }

    /** 대기 큐 적재 실패 — 그 알림은 유실된다 (plan.md §H R8). */
    public void queueWriteFailure() {
        registry.counter(QUEUE_WRITE_FAILURE).increment();
    }

    /** 대기 큐 항목 폐기 (사유별). */
    public void discarded(DiscardReason reason, int count) {
        if (count > 0) {
            discardCounter(reason).increment(count);
        }
    }

    private Counter sendCounter(Channel channel, Kind kind) {
        return Counter.builder(SEND)
                .description("sendMessage 응답 분류별 건수 (알림/요약 구분)")
                .tag(CHANNEL, tag(channel))
                .tag(RESULT, tag(kind))
                .register(registry);
    }

    private Counter discardCounter(DiscardReason reason) {
        return Counter.builder(QUEUE_DISCARDED)
                .description("대기 큐에서 사람에게 도달하지 못하고 폐기된 항목 수")
                .tag(REASON, tag(reason))
                .register(registry);
    }

    private Timer latencyTimer() {
        return Timer.builder(SEND_LATENCY)
                .description("결정 수신 → sendMessage 200 응답까지의 지연")
                .register(registry);
    }

    private static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    /** 발송 종류. */
    public enum Channel {
        /** 개별 알림. */
        ALERT,
        /** 대기 큐 요약. */
        SUMMARY
    }

    /** 대기 큐 항목 폐기 사유 (REQ-052 ⑤). */
    public enum DiscardReason {
        /** 보존 기간(24시간) 초과. */
        RETENTION_EXPIRED,
        /** 요약이 영구 오류(4xx)로 실패. */
        SUMMARY_PERMANENT_FAILURE
    }
}
