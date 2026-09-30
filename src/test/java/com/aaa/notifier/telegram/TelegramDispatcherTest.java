package com.aaa.notifier.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.notifier.filter.AlertDecision;
import com.aaa.notifier.telegram.TelegramFakes.LoggedRow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 디스패처 — 발송·응답 4분류 처리·safe_mode·단일 이관 지점 (REQ-004·021~024·031, design.md §3·§4).
 *
 * <p>가짜 협력자와 테스트 시계로 순서·대기 시간을 결정적으로 만든다. 디스패처 스레드 없이 {@link TelegramDispatcher#processNext()}로 버퍼를
 * 같은 스레드에서 비운다.
 */
@DisplayName("TelegramDispatcher — 발송 흐름·safe_mode·이관 지점")
class TelegramDispatcherTest {

    private static final SendOutcome OK_777 = SendOutcome.success(777L);
    private static final SendOutcome UNAVAILABLE = SendOutcome.transientFailure("HTTP 503");

    private DispatcherHarness h;

    @BeforeEach
    void setUp() {
        h = new DispatcherHarness();
    }

    private void send(String traceId) {
        h.dispatcher.submit(TelegramAlerts.candidate(traceId));
        h.drain();
    }

    private List<LoggedRow> rows(EventType type) {
        return h.log.of(type);
    }

    @Nested
    @DisplayName("AC-07 성공 (REQ-021·041)")
    class Success {

        @Test
        @DisplayName("SENT 행에 실제 보낸 본문과 message_id를 남기고 카운터를 0으로, stream:alert를 1건 발행한다")
        void success_recordsResetsAndPublishes() {
            // Arrange — 연속 실패 카운터를 2로 만든다
            h.api.always(UNAVAILABLE);
            send("fail-1");
            send("fail-2");
            assertThat(h.dispatcher.consecutiveFailures()).isEqualTo(2);
            h.api.always(OK_777);

            // Act
            send("trace-ok");

            // Assert
            assertThat(rows(EventType.SENT))
                    .singleElement()
                    .isEqualTo(
                            new LoggedRow(
                                    EventType.SENT,
                                    "TIMING",
                                    "trace-ok",
                                    h.api.sentTexts.getLast(),
                                    777L));
            assertThat(h.dispatcher.consecutiveFailures()).isZero();
            assertThat(h.publisher.entries)
                    .singleElement()
                    .satisfies(p -> assertThat(p.traceId()).isEqualTo("trace-ok"))
                    .satisfies(p -> assertThat(p.messageId()).isEqualTo(777L));
        }

        @Test
        @DisplayName("REQ-052 ② — 발송 지연 분포에 결정 수신 → 200 응답 지연을 기록한다")
        void success_recordsLatency() {
            send("trace-ok");

            assertThat(h.registry.get("notifier.telegram.send.latency").timer().count())
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("AC-08 429 (REQ-022)")
    class RateLimit {

        @Test
        @DisplayName("retry_after만큼 기다린 뒤 재발송해 SENT가 되고, 카운터는 바뀌지 않는다")
        void waitsRetryAfterThenSends() {
            h.api.respond(SendOutcome.rateLimited(Duration.ofSeconds(2), "429"), OK_777);

            send("trace-429");

            List<Instant> at = h.api.sentAt;
            assertThat(at).hasSize(2);
            assertThat(Duration.between(at.getFirst(), at.get(1)))
                    .isGreaterThanOrEqualTo(Duration.ofSeconds(2));
            assertThat(rows(EventType.SENT)).hasSize(1);
            assertThat(h.dispatcher.consecutiveFailures()).isZero();
        }

        @Test
        @DisplayName("① retry_after가 없으면 기본 대기(5초)를 쓴다")
        void missingRetryAfter_usesDefault() {
            h.api.respond(SendOutcome.rateLimited(null, "429"), OK_777);

            send("trace-429");

            assertThat(h.sleeper.sleeps).contains(Duration.ofSeconds(5));
        }

        @Test
        @DisplayName("② retry_after가 누적 상한보다 크면 기다리지 않고 대기 큐 + QUEUED, 추가 요청 없음")
        void retryAfterBeyondCap_queuesImmediately() {
            h.api.always(SendOutcome.rateLimited(Duration.ofSeconds(40), "429"));

            send("trace-429");

            assertThat(h.api.sentTexts).hasSize(1);
            assertThat(h.sleeper.sleeps).isEmpty();
            assertThat(h.queue.items)
                    .singleElement()
                    .extracting(QueuedAlert::reason)
                    .isEqualTo("rate_limit_cap");
            assertThat(rows(EventType.QUEUED)).hasSize(1);
        }

        @Test
        @DisplayName("③ 레이트 리밋 결과 카운터가 증가한다")
        void rateLimitCounter() {
            h.api.respond(SendOutcome.rateLimited(Duration.ofSeconds(1), "429"), OK_777);

            send("trace-429");

            assertThat(
                            h.counter(
                                    "notifier.telegram.send",
                                    "channel",
                                    "alert",
                                    "result",
                                    "rate_limited"))
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("④ 재발송 횟수 상한 3 — 정확히 4회 요청 후 큐잉, 카운터 불변, 다음 후보는 정상 발송")
        void resendCap_stopsAfterFourRequests() {
            h.api.always(SendOutcome.rateLimited(Duration.ofSeconds(1), "429"));

            send("trace-429");

            assertThat(h.api.sentTexts).hasSize(4);
            assertThat(h.queue.items).hasSize(1);
            assertThat(rows(EventType.QUEUED)).hasSize(1);
            assertThat(h.dispatcher.consecutiveFailures()).isZero();

            h.api.always(OK_777);
            send("trace-next");
            assertThat(rows(EventType.SENT))
                    .extracting(LoggedRow::traceId)
                    .containsExactly("trace-next");
        }

        @Test
        @DisplayName("⑤ 누적 대기 상한 30초 — retry_after=12면 두 번(24초) 기다린 뒤 세 번째는 기다리지 않고 큐잉")
        void cumulativeCap_queuesBeforeExceeding() {
            h.api.always(SendOutcome.rateLimited(Duration.ofSeconds(12), "429"));

            send("trace-429");

            assertThat(h.api.sentTexts).hasSize(3);
            assertThat(h.sleeper.total()).isEqualTo(Duration.ofSeconds(24));
            assertThat(rows(EventType.QUEUED)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("AC-09 일시 오류 (REQ-023)")
    class Transient {

        @Test
        @DisplayName("K=2면 정확히 3회 요청, 지수 백오프(1초→2초), 큐 +1·QUEUED 1건·카운터 +1")
        void exhaustsRetriesThenQueues() {
            h.api.always(UNAVAILABLE);

            send("trace-503");

            assertThat(h.api.sentTexts).hasSize(3);
            assertThat(h.sleeper.sleeps)
                    .containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
            assertThat(h.queue.items)
                    .singleElement()
                    .satisfies(
                            item -> {
                                assertThat(item.traceId()).isEqualTo("trace-503");
                                assertThat(item.reason()).isEqualTo("transient_exhausted");
                            });
            assertThat(rows(EventType.QUEUED)).hasSize(1);
            assertThat(h.dispatcher.consecutiveFailures()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("AC-10 영구 오류 (REQ-024)")
    class Permanent {

        @ParameterizedTest(name = "HTTP {0}")
        @ValueSource(ints = {400, 401, 403})
        @DisplayName("요청 1회, SEND_FAILED(본문 있음·message_id 없음), 큐·카운터 불변, stream:alert 없음")
        void permanent_recordsSendFailed(int status) {
            h.api.always(SendOutcome.permanentFailure("HTTP " + status));

            send("trace-4xx");

            assertThat(h.api.sentTexts).hasSize(1);
            assertThat(rows(EventType.SEND_FAILED))
                    .singleElement()
                    .satisfies(
                            row -> {
                                assertThat(row.text()).isNotBlank();
                                assertThat(row.messageId()).isNull();
                            });
            assertThat(h.queue.items).isEmpty();
            assertThat(h.dispatcher.consecutiveFailures()).isZero();
            assertThat(h.publisher.entries).isEmpty();
        }
    }

    @Nested
    @DisplayName("AC-11 safe_mode (REQ-031)")
    class SafeMode {

        private void failThree() {
            h.api.always(UNAVAILABLE);
            send("fail-1");
            send("fail-2");
            send("fail-3");
        }

        @Test
        @DisplayName("N=3 연속 소진 → 키가 AUTO가 되고 카운터는 0으로 돌아간다")
        void threeFailures_enterAuto() {
            failThree();

            assertThat(h.safeMode.raw()).isEqualTo("AUTO");
            assertThat(h.dispatcher.consecutiveFailures()).isZero();
        }

        @Test
        @DisplayName("① AUTO 동안 후보는 요청 없이 대기 큐 + QUEUED")
        void auto_queuesWithoutSending() {
            h.safeMode.set("AUTO");

            send("trace-auto");

            assertThat(h.api.sentTexts).isEmpty();
            assertThat(h.queue.items).hasSize(1);
            assertThat(rows(EventType.QUEUED)).hasSize(1);
        }

        @Test
        @DisplayName("② 운영자 ON 동안 후보는 요청 없이 DRYRUN만 — 큐·QUEUED·stream:alert 없음")
        void operatorOn_dryRunOnly() {
            h.safeMode.set("ON");

            send("trace-on");
            h.safeMode.set("OFF");
            h.drain();

            assertThat(h.api.sentTexts).isEmpty();
            assertThat(h.dryRun.decisions)
                    .extracting(AlertDecision::traceId)
                    .containsExactly("trace-on");
            assertThat(h.queue.items).isEmpty();
            assertThat(h.log.rows).isEmpty();
            assertThat(h.publisher.entries).isEmpty();
        }

        @Test
        @DisplayName("④ R-A — N번째 후보의 마지막 시도 중 운영자가 ON을 켜면 ON이 유지되고 그 후보는 DRYRUN, 카운터는 0")
        void operatorOnDuringLastAttempt_keepsOn() {
            // Arrange — 카운터 N-1
            h.api.always(UNAVAILABLE);
            send("fail-1");
            send("fail-2");
            int requestsBefore = h.api.sentTexts.size();
            // N번째 후보의 세 번째(마지막) 시도가 전송 중일 때 운영자가 ON을 켠다
            h.api.onSend(
                    n -> {
                        if (n == requestsBefore + 3) {
                            h.safeMode.set("ON");
                        }
                    });

            // Act
            send("fail-3");

            // Assert
            assertThat(h.safeMode.raw()).isEqualTo("ON");
            assertThat(h.dryRun.decisions)
                    .extracting(AlertDecision::traceId)
                    .containsExactly("fail-3");
            assertThat(h.queue.items)
                    .extracting(QueuedAlert::traceId)
                    .containsExactly("fail-1", "fail-2");

            // 카운터가 0으로 돌아갔음을 행동으로 확인 — OFF 후 1건 소진으로는 AUTO가 되지 않는다
            h.safeMode.set("OFF");
            h.api.onSend(n -> {});
            send("fail-4");
            assertThat(h.safeMode.raw()).isEqualTo("OFF");
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"1", "true", ""})
        @DisplayName("⑤ 미인식 값은 ON처럼 — 요청 없이 DRYRUN")
        void unknownValue_behavesLikeOn(String raw) {
            h.safeMode.set(raw);

            send("trace-unknown");

            assertThat(h.api.sentTexts).isEmpty();
            assertThat(h.dryRun.decisions).hasSize(1);
        }

        @Test
        @DisplayName("⑥ R-D — 1건 전송 중 ON이 켜지면 그 1건은 결과대로 기록되고 버퍼의 2건은 요청 없이 DRYRUN, 큐 불변")
        void operatorOnWhileBuffered() {
            h.api.onSend(n -> h.safeMode.set("ON"));
            h.dispatcher.submit(TelegramAlerts.candidate("in-flight"));
            h.dispatcher.submit(TelegramAlerts.candidate("buffered-1"));
            h.dispatcher.submit(TelegramAlerts.candidate("buffered-2"));

            h.drain();

            assertThat(h.api.sentTexts).hasSize(1);
            assertThat(rows(EventType.SENT))
                    .extracting(LoggedRow::traceId)
                    .containsExactly("in-flight");
            assertThat(h.dryRun.decisions)
                    .extracting(AlertDecision::traceId)
                    .containsExactly("buffered-1", "buffered-2");
            assertThat(h.queue.items).isEmpty();
        }

        @Test
        @DisplayName("R-F — safe_mode를 읽지 못하면 OFF로 간주하고 발송을 시도한다 (plan.md §D.10)")
        void readFailure_sendsAnyway() {
            h.safeMode.set("ON");
            h.safeMode.failReads(true);

            send("trace-redis-down");

            assertThat(rows(EventType.SENT)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("AC-04 발송 간격·순서·버퍼·종료 (REQ-004)")
    class Dispatch {

        @Test
        @DisplayName("① ② 연속 요청 사이 최소 간격(1초)을 지키고 투입 순서대로 보낸다")
        void pacingAndOrder() {
            h.dispatcher.submit(TelegramAlerts.candidate("a"));
            h.dispatcher.submit(TelegramAlerts.candidate("b"));
            h.dispatcher.submit(TelegramAlerts.candidate("c"));

            h.drain();

            List<Instant> at = h.api.sentAt;
            assertThat(Duration.between(at.getFirst(), at.get(1)))
                    .isGreaterThanOrEqualTo(Duration.ofSeconds(1));
            assertThat(Duration.between(at.get(1), at.get(2)))
                    .isGreaterThanOrEqualTo(Duration.ofSeconds(1));
            assertThat(rows(EventType.SENT))
                    .extracting(LoggedRow::traceId)
                    .containsExactly("a", "b", "c");
        }

        @Test
        @DisplayName("경계 — 버퍼 상한(2)을 넘는 3번째 후보는 즉시 대기 큐 + QUEUED")
        void bufferFull_queuesImmediately() {
            DispatcherHarness small = new DispatcherHarness(2);

            small.dispatcher.submit(TelegramAlerts.candidate("a"));
            small.dispatcher.submit(TelegramAlerts.candidate("b"));
            small.dispatcher.submit(TelegramAlerts.candidate("c"));

            assertThat(small.queue.items)
                    .singleElement()
                    .satisfies(
                            item -> {
                                assertThat(item.traceId()).isEqualTo("c");
                                assertThat(item.reason()).isEqualTo("buffer_full");
                            });
            assertThat(small.log.of(EventType.QUEUED)).hasSize(1);
        }

        @Test
        @DisplayName("경계 — 운영자 ON이 관측된 상태의 버퍼 초과 후보는 DRYRUN")
        void bufferFull_underOperatorOn_dryRun() {
            DispatcherHarness small = new DispatcherHarness(1);
            small.safeMode.set("ON");
            small.safeMode.read();

            small.dispatcher.submit(TelegramAlerts.candidate("a"));
            small.dispatcher.submit(TelegramAlerts.candidate("b"));

            assertThat(small.queue.items).isEmpty();
            assertThat(small.dryRun.decisions)
                    .extracting(AlertDecision::traceId)
                    .containsExactly("b");
        }

        @Test
        @DisplayName("③ 정상 종료 시 미발송 후보는 대기 큐로 옮기고 각각 QUEUED")
        void shutdown_movesRemainderToQueue() {
            h.dispatcher.submit(TelegramAlerts.candidate("a"));
            h.dispatcher.submit(TelegramAlerts.candidate("b"));

            h.dispatcher.drainOnShutdown();

            assertThat(h.queue.items)
                    .extracting(QueuedAlert::reason)
                    .containsExactly("shutdown", "shutdown");
            assertThat(rows(EventType.QUEUED)).hasSize(2);
            assertThat(h.api.sentTexts).isEmpty();
        }

        @Test
        @DisplayName("④ 운영자 ON 상태의 정상 종료 — 잔량은 큐 대신 DRYRUN, QUEUED 0건")
        void shutdown_underOperatorOn_dryRun() {
            h.dispatcher.submit(TelegramAlerts.candidate("a"));
            h.dispatcher.submit(TelegramAlerts.candidate("b"));
            h.safeMode.set("ON");

            h.dispatcher.drainOnShutdown();

            assertThat(h.queue.items).isEmpty();
            assertThat(rows(EventType.QUEUED)).isEmpty();
            assertThat(h.dryRun.decisions).hasSize(2);
        }

        @Test
        @DisplayName("종료 뒤 도착한 후보는 버퍼에 남기지 않고 곧바로 이관한다")
        void afterShutdown_divertsDirectly() {
            h.dispatcher.drainOnShutdown();

            h.dispatcher.submit(TelegramAlerts.candidate("late"));

            assertThat(h.queue.items).extracting(QueuedAlert::traceId).containsExactly("late");
        }
    }

    @Test
    @DisplayName("AC-13 ⑥ — 성공 직후 대기 큐가 비어 있지 않으면 요약을 요청한다")
    void successWithNonEmptyQueue_requestsSummary() {
        h.queue.append(
                QueuedAlert.of(
                        TelegramAlerts.candidate("old"),
                        DispatcherHarness.START.toLocalDateTime(),
                        DivertReason.SHUTDOWN));

        send("trace-ok");

        assertThat(h.dispatcher.summaryRequested()).isTrue();
    }
}
