package com.aaa.notifier.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 소비 루프 시맨틱 테스트 — 재소유 순서·ACK 조건·즉시 DLQ (REQ-NOTIFIER-CONSUMER-011/012/013, AC-5/AC-7/AC-10, AC-14
 * 시나리오 B 단언 ⑪⑫).
 *
 * <p><b>재소유 순서가 이 SPEC의 가장 조용한 결함 경계다.</b> 재할당 명령(XCLAIM)은 그 자체가 재전달 횟수를 1 증가시키므로, 판독(XPENDING)을
 * 재할당 뒤에 두면 재전달 3회 메시지가 4회로 보여 DLQ 임계 판정이 한 회차씩 밀린다. 경계값 3/4를 직접 고정하는 것이 그 순서 보장의 유일한 기계적 증거다.
 */
@DisplayName("StreamConsumerWorker — 소비 루프 시맨틱 (REQ-011/012/013, AC-5/AC-7/AC-10)")
class StreamConsumerWorkerTest {

    private static final String STREAM_KEY = "stream:tick:domestic";
    private static final String CONSUMER_NAME = "notifier-tick-domestic";
    private static final Duration IDLE_THRESHOLD = Duration.ofSeconds(60);

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final StreamOperations<String, String, String> streamOperations =
            mock(StreamOperations.class);

    private final StreamObservationHandler handler = mock(StreamObservationHandler.class);
    private final DeadLetterPublisher deadLetterPublisher = mock(DeadLetterPublisher.class);

    private StreamConsumerWorker worker;

    @BeforeEach
    void setUp() {
        when(redisTemplate.<String, String>opsForStream()).thenReturn(streamOperations);
        worker =
                new StreamConsumerWorker(
                        ConsumedStream.TICK_DOMESTIC,
                        redisTemplate,
                        properties(),
                        new TickPayloadParser(),
                        new SignalPayloadParser(),
                        handler,
                        deadLetterPublisher);
        stubNoPending();
        stubNoNewMessages();
    }

    @Nested
    @DisplayName("AC-5 — 재소유 순서(판독 선행) + DLQ 임계 경계 3/4")
    class ReclaimOrderAndThreshold {

        @Test
        @DisplayName("① 판독(XPENDING)이 재할당(XCLAIM)보다 먼저 호출된다")
        void readsPendingBeforeClaiming() {
            stubPending(pendingMessage("1-0", 3));
            stubClaim(tickRecord("1-0"));

            worker.pollOnce();

            InOrder order = inOrder(streamOperations);
            order.verify(streamOperations).pending(eq(STREAM_KEY), anyString(), any(), anyLong());
            order.verify(streamOperations)
                    .claim(eq(STREAM_KEY), anyString(), anyString(), any(XClaimOptions.class));
        }

        @Test
        @DisplayName("② 재전달 3회(임계 이하) 메시지는 재소유되고 DLQ로 가지 않는다")
        void deliveryCountAtThreshold_isReclaimedNotDeadLettered() {
            stubPending(pendingMessage("1-0", 3));
            stubClaim(tickRecord("1-0"));

            worker.pollOnce();

            verify(handler).onTradeTick(any());
            verify(deadLetterPublisher, never()).transfer(any(), any(), any(), any());
        }

        @Test
        @DisplayName("③ 재전달 4회(임계 초과) 메시지는 재소유하지 않고 DLQ로 이관된다")
        void deliveryCountOverThreshold_isDeadLettered() {
            stubPending(pendingMessage("9-0", 4));
            when(streamOperations.range(eq(STREAM_KEY), any(Range.class)))
                    .thenReturn(List.of(tickRecord("9-0")));

            worker.pollOnce();

            verify(deadLetterPublisher)
                    .transfer(
                            eq(ConsumedStream.TICK_DOMESTIC),
                            eq("9-0"),
                            any(),
                            eq(DeadLetterPublisher.Reason.MAX_DELIVERY_EXCEEDED));
            verify(streamOperations, never())
                    .claim(anyString(), anyString(), anyString(), any(XClaimOptions.class));
        }

        @Test
        @DisplayName("유휴 임계 미만 메시지는 재소유 대상이 아니다")
        void belowIdleThreshold_isNotReclaimed() {
            stubPending(
                    new PendingMessage(
                            RecordId.of("1-0"),
                            Consumer.from(ConsumedStream.CONSUMER_GROUP, CONSUMER_NAME),
                            Duration.ofSeconds(1),
                            1L));

            worker.pollOnce();

            verify(streamOperations, never())
                    .claim(anyString(), anyString(), anyString(), any(XClaimOptions.class));
            verify(deadLetterPublisher, never()).transfer(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("AC-7 — 확인응답은 처리 성공 이후에만")
    class AcknowledgeOnlyAfterSuccess {

        @Test
        @DisplayName("① 하류 전달이 예외로 끝나면 확인응답하지 않는다 (미확인으로 남아 재소유 대상이 된다)")
        void handlerFailure_doesNotAcknowledge() {
            stubNewMessages(tickRecord("5-0"));
            doThrow(new IllegalStateException("하류 실패")).when(handler).onTradeTick(any());

            worker.pollOnce();

            verify(streamOperations, never()).acknowledge(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("② 하류 전달이 정상 반환하면 확인응답한다")
        void handlerSuccess_acknowledges() {
            stubNewMessages(tickRecord("5-0"));

            worker.pollOnce();

            verify(streamOperations).acknowledge(STREAM_KEY, ConsumedStream.CONSUMER_GROUP, "5-0");
        }

        @Test
        @DisplayName("③ 하류 전달이 XACK보다 먼저 호출된다")
        void deliversBeforeAcknowledging() {
            stubNewMessages(tickRecord("5-0"));

            worker.pollOnce();

            InOrder order = inOrder(handler, streamOperations);
            order.verify(handler).onTradeTick(any());
            order.verify(streamOperations).acknowledge(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("하류 실패가 소비 루프를 중단시키지 않는다 (예외가 전파되지 않음)")
        void handlerFailure_doesNotPropagate() {
            stubNewMessages(tickRecord("5-0"));
            doThrow(new IllegalStateException("하류 실패")).when(handler).onTradeTick(any());

            assertThat(worker.pollOnce()).isZero();
        }
    }

    @Nested
    @DisplayName("AC-10 — 해석 불가 페이로드 즉시 이관 (재시도 없음)")
    class ImmediateDeadLetter {

        @Test
        @DisplayName("③ 미지의 trId는 첫 회차에 DLQ로 가고 하류 핸들러가 호출되지 않는다")
        void unknownTrId_goesToDlqWithoutHandlerCall() {
            stubNewMessages(
                    entryRecord(
                            "7-0",
                            KisFrameFixtures.tickEntry(
                                    "005930", "H0STXXX0", "a^b", KisFrameFixtures.TRACE_ID)));

            worker.pollOnce();

            verify(deadLetterPublisher)
                    .transfer(
                            eq(ConsumedStream.TICK_DOMESTIC),
                            eq("7-0"),
                            any(),
                            eq(DeadLetterPublisher.Reason.PAYLOAD_UNPARSEABLE));
            verify(handler, never()).onTradeTick(any());
        }

        @Test
        @DisplayName("나누어떨어지지 않는 data도 첫 회차에 DLQ로 이관된다")
        void nonDivisibleData_goesToDlqOnFirstPass() {
            stubNewMessages(
                    entryRecord(
                            "7-1",
                            KisFrameFixtures.tickEntry(
                                    "005930",
                                    "H0STCNT0",
                                    KisFrameFixtures.DOMESTIC_TRADE_RECORD + "^extra",
                                    KisFrameFixtures.TRACE_ID)));

            worker.pollOnce();

            verify(deadLetterPublisher)
                    .transfer(
                            any(),
                            eq("7-1"),
                            any(),
                            eq(DeadLetterPublisher.Reason.PAYLOAD_UNPARSEABLE));
        }
    }

    @Nested
    @DisplayName("AC-3 ③ / AC-8 — 소비 루프는 하류 실패·Redis 오류에도 종료되지 않는다")
    class LoopSurvival {

        private static final Duration AWAIT = Duration.ofSeconds(5);

        @Test
        @DisplayName("AC-3 ③ 하류 핸들러가 호출마다 예외를 던져도 run() 루프가 계속 돌고 확인응답은 없다")
        void handlerThrowingOnEveryCall_doesNotTerminateLoop() throws InterruptedException {
            // Arrange — 같은 메시지가 매 회차 다시 읽히고 핸들러는 매번 실패한다.
            stubNewMessages(tickRecord("5-0"));
            CountDownLatch threeFailures = new CountDownLatch(3);
            doAnswer(
                            invocation -> {
                                threeFailures.countDown();
                                throw new IllegalStateException("하류 실패");
                            })
                    .when(handler)
                    .onTradeTick(any());
            Thread loop = Thread.ofVirtual().unstarted(worker);

            // Act
            loop.start();
            boolean reachedThreeFailures = threeFailures.await(AWAIT.toSeconds(), TimeUnit.SECONDS);

            // Assert — 실패가 3번 이상 났는데도 루프 스레드는 살아 있다.
            assertThat(reachedThreeFailures).isTrue();
            assertThat(loop.isAlive()).isTrue();
            verify(streamOperations, never()).acknowledge(anyString(), anyString(), anyString());

            worker.stop();
            loop.join(AWAIT);
            assertThat(loop.isAlive()).as("stop() 이후에는 정상 종료한다").isFalse();
        }

        @Test
        @DisplayName("AC-8 Redis 호출이 계속 실패해도 run() 루프가 백오프하며 재시도할 뿐 종료되지 않는다")
        void redisFailingOnEveryCall_doesNotTerminateLoop() throws InterruptedException {
            // Arrange — 판독부터 매번 연결 실패, 백오프는 10ms로 짧게 준다.
            AtomicInteger attempts = new AtomicInteger();
            CountDownLatch threeAttempts = new CountDownLatch(3);
            when(streamOperations.pending(anyString(), anyString(), any(), anyLong()))
                    .thenAnswer(
                            invocation -> {
                                attempts.incrementAndGet();
                                threeAttempts.countDown();
                                throw new RedisConnectionFailureException("연결 두절");
                            });
            StreamConsumerWorker fastBackoff =
                    new StreamConsumerWorker(
                            ConsumedStream.TICK_DOMESTIC,
                            redisTemplate,
                            new StreamConsumerProperties(
                                    true,
                                    Duration.ofSeconds(2),
                                    10,
                                    IDLE_THRESHOLD,
                                    3,
                                    Duration.ofMillis(10),
                                    500),
                            new TickPayloadParser(),
                            new SignalPayloadParser(),
                            handler,
                            deadLetterPublisher);
            Thread loop = Thread.ofVirtual().unstarted(fastBackoff);

            // Act
            loop.start();
            boolean retried = threeAttempts.await(AWAIT.toSeconds(), TimeUnit.SECONDS);

            // Assert
            assertThat(retried).isTrue();
            assertThat(loop.isAlive()).isTrue();
            assertThat(attempts.get()).isGreaterThanOrEqualTo(3);

            fastBackoff.stop();
            loop.interrupt();
            loop.join(AWAIT);
            assertThat(loop.isAlive()).isFalse();
        }
    }

    @Nested
    @DisplayName("예외 경계 — 무엇을 격리하고 무엇을 드러내는가")
    class ExceptionBoundary {

        @Test
        @DisplayName("확인응답 자체가 Redis 오류로 실패하면 삼키지 않고 루프의 백오프 경로로 전파한다")
        void acknowledgeFailure_propagatesToLoopBackoff() {
            stubNewMessages(tickRecord("5-0"));
            doThrow(new RedisConnectionFailureException("연결 두절"))
                    .when(streamOperations)
                    .acknowledge(anyString(), anyString(), anyString());

            assertThatThrownBy(worker::pollOnce).isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("하류가 Error를 던지면 격리하지 않고 그대로 드러낸다 (VM 오류를 삼키지 않는다)")
        void errorFromHandler_isNotSwallowed() {
            stubNewMessages(tickRecord("5-0"));
            doThrow(new AssertionError("Error는 격리 대상이 아니다")).when(handler).onTradeTick(any());

            assertThatThrownBy(worker::pollOnce).isInstanceOf(AssertionError.class);
            verify(streamOperations, never()).acknowledge(anyString(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("AC-14 시나리오 B 단언 ⑪⑫ — ZDIV 폴백은 DLQ 대상이 아니다")
    class ZdivFallbackIsNotDeadLettered {

        @Test
        @DisplayName("⑪⑫ 자리수 불일치 해외 체결은 DLQ 0건 + 정상 확인응답된다")
        void scaleMismatch_isDeliveredAndAcknowledgedWithoutDlq() {
            StreamConsumerWorker overseasWorker = overseasWorker();
            String mismatched =
                    KisFrameFixtures.withField(
                            KisFrameFixtures.OVERSEAS_TRADE_RECORD, 11, "2984700");
            stubNewMessages(
                    entryRecord(
                            "8-0",
                            KisFrameFixtures.tickEntry(
                                    "DNASAAPL",
                                    "HDFSCNT0",
                                    mismatched,
                                    KisFrameFixtures.TRACE_ID)));

            overseasWorker.pollOnce();

            verify(deadLetterPublisher, never()).transfer(any(), any(), any(), any());
            verify(streamOperations)
                    .acknowledge("stream:tick:overseas", ConsumedStream.CONSUMER_GROUP, "8-0");
            verify(handler).onTradeTick(any());
        }
    }

    @Nested
    @DisplayName("배치 엔트리 전달 (AC-12 소비 경로)")
    class BatchDelivery {

        @Test
        @DisplayName("배치 7건이 하류에 7회 개별 전달된 뒤 1회만 확인응답된다")
        void batchEntry_deliversPerRecord_andAcknowledgesOnce() {
            String data =
                    KisFrameFixtures.batch(
                            KisFrameFixtures.DOMESTIC_TRADE_RECORD, 2, 12, 313_000L, 825L, 7);
            stubNewMessages(
                    entryRecord(
                            "6-0",
                            KisFrameFixtures.tickEntry(
                                    "005930", "H0STCNT0", data, KisFrameFixtures.TRACE_ID)));

            worker.pollOnce();

            verify(handler, times(7)).onTradeTick(any());
            verify(streamOperations, times(1)).acknowledge(anyString(), anyString(), anyString());
        }
    }

    // --- 스텁 헬퍼 -------------------------------------------------------

    private StreamConsumerProperties properties() {
        return new StreamConsumerProperties(
                true, Duration.ofSeconds(2), 10, IDLE_THRESHOLD, 3, Duration.ofSeconds(5), 500);
    }

    private StreamConsumerWorker overseasWorker() {
        return new StreamConsumerWorker(
                ConsumedStream.TICK_OVERSEAS,
                redisTemplate,
                properties(),
                new TickPayloadParser(),
                new SignalPayloadParser(),
                handler,
                deadLetterPublisher);
    }

    private void stubNoPending() {
        when(streamOperations.pending(anyString(), anyString(), any(), anyLong()))
                .thenReturn(new PendingMessages(ConsumedStream.CONSUMER_GROUP, List.of()));
    }

    private void stubPending(PendingMessage... messages) {
        when(streamOperations.pending(anyString(), anyString(), any(), anyLong()))
                .thenReturn(new PendingMessages(ConsumedStream.CONSUMER_GROUP, List.of(messages)));
    }

    @SafeVarargs
    private void stubClaim(MapRecord<String, String, String>... records) {
        when(streamOperations.claim(
                        anyString(), anyString(), anyString(), any(XClaimOptions.class)))
                .thenReturn(List.of(records));
    }

    private void stubNoNewMessages() {
        when(streamOperations.read(
                        any(Consumer.class),
                        any(StreamReadOptions.class),
                        any(StreamOffset[].class)))
                .thenReturn(List.of());
    }

    @SafeVarargs
    private void stubNewMessages(MapRecord<String, String, String>... records) {
        when(streamOperations.read(
                        any(Consumer.class),
                        any(StreamReadOptions.class),
                        any(StreamOffset[].class)))
                .thenReturn(List.of(records));
    }

    private PendingMessage pendingMessage(String id, long deliveryCount) {
        return new PendingMessage(
                RecordId.of(id),
                Consumer.from(ConsumedStream.CONSUMER_GROUP, CONSUMER_NAME),
                IDLE_THRESHOLD.plusSeconds(1),
                deliveryCount);
    }

    private MapRecord<String, String, String> tickRecord(String id) {
        return entryRecord(
                id,
                KisFrameFixtures.tickEntry(
                        "005930",
                        "H0STCNT0",
                        KisFrameFixtures.DOMESTIC_TRADE_RECORD,
                        KisFrameFixtures.TRACE_ID));
    }

    private MapRecord<String, String, String> entryRecord(String id, Map<String, String> fields) {
        return MapRecord.create(STREAM_KEY, fields).withId(RecordId.of(id));
    }
}
