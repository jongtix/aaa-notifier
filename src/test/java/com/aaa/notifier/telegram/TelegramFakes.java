package com.aaa.notifier.telegram;

import com.aaa.notifier.filter.AlertDecision;
import com.aaa.notifier.filter.AlertDecisionSink;
import io.lettuce.core.RedisCommandInterruptedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** 디스패처 단위 테스트용 가짜 협력자 — 외부 시스템(텔레그램·Redis·MySQL) 없이 순서를 결정적으로 만든다. */
interface TelegramFakes {

    /** 테스트가 직접 움직이는 시계. */
    final class MutableClock extends Clock {

        private final ZoneId zone;
        private Instant now;

        MutableClock(Instant now, ZoneId zone) {
            super();
            this.now = now;
            this.zone = zone;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return new MutableClockView(this, newZone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** {@link MutableClock}을 다른 시간대로 보는 뷰 — 원본 시계를 따라 움직인다. */
    final class MutableClockView extends Clock {

        private final MutableClock source;
        private final ZoneId zone;

        MutableClockView(MutableClock source, ZoneId zone) {
            super();
            this.source = source;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return new MutableClockView(source, newZone);
        }

        @Override
        public Instant instant() {
            return source.instant();
        }
    }

    /** 실제로 잠들지 않고 시계를 대기 시간만큼 움직이며 대기 기록을 남긴다. */
    final class RecordingSleeper implements Sleeper {

        final List<Duration> sleeps = new ArrayList<>();
        private final MutableClock clock;
        private boolean interruptNext;

        RecordingSleeper(MutableClock clock) {
            this.clock = clock;
        }

        /** 다음 대기에서 정상 종료 인터럽트를 흉내 낸다 ({@code Thread.sleep}처럼 플래그를 지우고 던진다). */
        void interruptNextSleep() {
            this.interruptNext = true;
        }

        @Override
        public void sleep(Duration duration) throws InterruptedException {
            if (interruptNext) {
                interruptNext = false;
                throw new InterruptedException("shutdown");
            }
            sleeps.add(duration);
            clock.advance(duration);
        }

        Duration total() {
            return sleeps.stream().reduce(Duration.ZERO, Duration::plus);
        }
    }

    /**
     * 응답을 차례로 돌려주는 가짜 Bot API. 응답이 바닥나면 마지막 응답을 반복한다. {@link #onSend}는 응답을 돌려주기 직전(= 요청이 "전송 중"인
     * 시점)에 호출된다.
     */
    final class ScriptedApi implements TelegramApi {

        final List<String> sentTexts = new CopyOnWriteArrayList<>();
        final List<Instant> sentAt = new CopyOnWriteArrayList<>();
        private final Deque<SendOutcome> responses = new ArrayDeque<>();
        private final Clock clock;
        private SendOutcome last = SendOutcome.success(1L);
        private boolean probeResult = true;
        private int probeCount;
        private Consumer<Integer> sendHook = n -> {};
        private Runnable onProbe = () -> {};

        ScriptedApi(Clock clock) {
            this.clock = clock;
        }

        ScriptedApi respond(SendOutcome... outcomes) {
            responses.addAll(List.of(outcomes));
            return this;
        }

        ScriptedApi always(SendOutcome outcome) {
            responses.clear();
            last = outcome;
            return this;
        }

        ScriptedApi onSend(Consumer<Integer> hook) {
            this.sendHook = hook;
            return this;
        }

        ScriptedApi probe(boolean result, Runnable hook) {
            this.probeResult = result;
            this.onProbe = hook;
            return this;
        }

        int probeCalls() {
            return probeCount;
        }

        @Override
        public SendOutcome sendMessage(String text) {
            sentTexts.add(text);
            sentAt.add(clock.instant());
            sendHook.accept(sentTexts.size());
            if (!responses.isEmpty()) {
                last = responses.poll();
            }
            return last;
        }

        @Override
        public boolean probe() {
            probeCount++;
            onProbe.run();
            return probeResult;
        }
    }

    /** 원래 문자열로 동작하는 인메모리 safe_mode 키 (조건부 쓰기 포함). */
    final class InMemorySafeMode implements SafeModeStore {

        private String value;
        private SafeModeState observed = SafeModeState.OFF;
        private boolean readFails;

        void set(String value) {
            this.value = value;
        }

        String raw() {
            return value;
        }

        void failReads(boolean fails) {
            this.readFails = fails;
        }

        @Override
        public SafeModeReading read() {
            // Lettuce처럼 인터럽트된 스레드에서는 명령이 실패한다
            if (readFails || Thread.currentThread().isInterrupted()) {
                return SafeModeReading.failed();
            }
            SafeModeReading reading = SafeModeReading.of(value);
            observed = reading.state();
            return reading;
        }

        @Override
        public SafeModeState lastObserved() {
            return observed;
        }

        @Override
        public AutoEntry enterAuto() {
            SafeModeReading reading = read();
            if (reading.readFailed()) {
                return AutoEntry.FAILED;
            }
            if (reading.state() != SafeModeState.OFF) {
                return AutoEntry.REJECTED;
            }
            value = "AUTO";
            observed = SafeModeState.AUTO;
            return AutoEntry.WRITTEN;
        }

        @Override
        public boolean releaseAuto(SafeModeReading reading) {
            boolean sameValue = value == null ? reading.raw() == null : value.equals(reading.raw());
            if (reading.state() != SafeModeState.AUTO || !sameValue) {
                return false;
            }
            value = "OFF";
            observed = SafeModeState.OFF;
            return true;
        }
    }

    /** 인메모리 대기 큐. */
    final class InMemoryQueue implements PendingQueue {

        final List<QueuedAlert> items = new CopyOnWriteArrayList<>();

        @Override
        public void append(QueuedAlert alert) {
            // Lettuce처럼 인터럽트된 스레드에서는 명령이 실패한다
            if (Thread.currentThread().isInterrupted()) {
                throw new RedisCommandInterruptedException(new InterruptedException("interrupted"));
            }
            items.add(alert);
        }

        @Override
        public QueueSnapshot snapshot() {
            return new QueueSnapshot(List.copyOf(items), items.size());
        }

        @Override
        public void removeFirst(int count) {
            for (int i = 0; i < count && !items.isEmpty(); i++) {
                items.removeFirst();
            }
        }

        @Override
        public long size() {
            return items.size();
        }
    }

    /** 기록된 {@code notification_log} 행. */
    record LoggedRow(
            EventType type, String notificationType, String traceId, String text, Long messageId) {}

    /** {@code notification_log} 기록기. */
    final class RecordingLog implements NotificationLog {

        final List<LoggedRow> rows = new CopyOnWriteArrayList<>();

        @Override
        public void alert(
                EventType type,
                AlertDecision decision,
                String messageText,
                Long telegramMessageId) {
            rows.add(
                    new LoggedRow(
                            type, "TIMING", decision.traceId(), messageText, telegramMessageId));
        }

        @Override
        public void summary(
                EventType type, String traceId, String messageText, Long telegramMessageId) {
            rows.add(new LoggedRow(type, "QUEUE_SUMMARY", traceId, messageText, telegramMessageId));
        }

        List<LoggedRow> of(EventType type) {
            return rows.stream().filter(row -> row.type() == type).toList();
        }
    }

    /** 발행 기록 1건. */
    record Published(String traceId, Long messageId, OffsetDateTime sentAt) {}

    /** {@code stream:alert} 기록기. */
    final class RecordingPublisher implements AlertPublisher {

        final List<Published> entries = new CopyOnWriteArrayList<>();

        @Override
        public void publish(AlertDecision decision, Long telegramMessageId, OffsetDateTime sentAt) {
            entries.add(new Published(decision.traceId(), telegramMessageId, sentAt));
        }
    }

    /** DRYRUN 싱크 기록기 — FILTER-001 DRYRUN 싱크 자리. */
    final class RecordingDryRun implements AlertDecisionSink {

        final List<AlertDecision> decisions = new CopyOnWriteArrayList<>();

        @Override
        public void onDecision(AlertDecision decision) {
            decisions.add(decision);
        }
    }
}
