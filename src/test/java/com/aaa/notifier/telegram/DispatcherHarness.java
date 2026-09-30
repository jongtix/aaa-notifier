package com.aaa.notifier.telegram;

import com.aaa.notifier.filter.FilterMetrics;
import com.aaa.notifier.filter.MarketSession;
import com.aaa.notifier.telegram.TelegramFakes.InMemoryQueue;
import com.aaa.notifier.telegram.TelegramFakes.InMemorySafeMode;
import com.aaa.notifier.telegram.TelegramFakes.MutableClock;
import com.aaa.notifier.telegram.TelegramFakes.RecordingDryRun;
import com.aaa.notifier.telegram.TelegramFakes.RecordingLog;
import com.aaa.notifier.telegram.TelegramFakes.RecordingPublisher;
import com.aaa.notifier.telegram.TelegramFakes.RecordingSleeper;
import com.aaa.notifier.telegram.TelegramFakes.ScriptedApi;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.ZonedDateTime;

/**
 * 가짜 협력자로 디스패처 한 벌을 조립한다. 수치는 plan.md §F 잠정값과 같다(최소 간격 1초·K=2·백오프 1→2초·429 기본 5초·누적 30초·재발송
 * 3회·N=3·보존 24시간·목록 10건).
 */
final class DispatcherHarness {

    static final ZonedDateTime START =
            ZonedDateTime.of(2026, 9, 29, 10, 0, 0, 0, MarketSession.KST);

    final MutableClock clock = new MutableClock(START.toInstant(), MarketSession.KST);
    final RecordingSleeper sleeper = new RecordingSleeper(clock);
    final ScriptedApi api = new ScriptedApi(clock);
    final InMemorySafeMode safeMode = new InMemorySafeMode();
    final InMemoryQueue queue = new InMemoryQueue();
    final RecordingLog log = new RecordingLog();
    final RecordingPublisher publisher = new RecordingPublisher();
    final RecordingDryRun dryRun = new RecordingDryRun();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final TelegramMetrics metrics = TelegramMetrics.create(registry);
    final TelegramDispatcher dispatcher;

    DispatcherHarness() {
        this(100);
    }

    DispatcherHarness(int bufferCapacity) {
        this(bufferCapacity, policy(3));
    }

    DispatcherHarness(int bufferCapacity, DeliveryPolicy policy) {
        DeliveryContext context =
                new DeliveryContext(
                        api,
                        safeMode,
                        queue,
                        log,
                        publisher,
                        dryRun,
                        new TelegramMessageFormatter(),
                        metrics,
                        FilterMetrics.create(registry),
                        clock,
                        sleeper,
                        policy);
        dispatcher = new TelegramDispatcher(context, bufferCapacity);
    }

    static DeliveryPolicy policy(int maxResends) {
        return new DeliveryPolicy(
                Duration.ofSeconds(1),
                2,
                Duration.ofSeconds(1),
                2,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                maxResends,
                3,
                Duration.ofHours(24),
                10);
    }

    /** 버퍼에 넣은 후보를 모두 처리한다 (디스패처 스레드 없이 같은 스레드에서). */
    void drain() {
        boolean more = true;
        while (more) {
            more = dispatcher.processNext();
        }
    }

    double counter(String name, String... tags) {
        Counter found = registry.find(name).tags(tags).counter();
        return found == null ? 0.0 : found.count();
    }
}
