package com.aaa.notifier.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 소비 단위 생명주기 테스트 (REQ-NOTIFIER-CONSUMER-002/003, AC-4).
 *
 * <p>종료가 제때 끝나는지가 이 테스트의 핵심이다 — 블로킹 읽기 루프는 종료 신호를 놓치면 {@code
 * spring.lifecycle.timeout-per-shutdown-phase}(30s)를 통째로 잡아먹고, 그 증상은 배포 때 "컨테이너가 안 죽는다"로만 보인다.
 */
@DisplayName("StreamConsumerRunner — 소비 단위 생명주기 (REQ-002/003, AC-4)")
class StreamConsumerRunnerTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final StreamOperations<String, String, String> streamOperations =
            mock(StreamOperations.class);

    private StreamConsumerRunner runner;

    @BeforeEach
    void setUp() {
        when(redisTemplate.<String, String>opsForStream()).thenReturn(streamOperations);
        when(streamOperations.pending(anyString(), anyString(), any(), anyLong()))
                .thenReturn(new PendingMessages(ConsumedStream.CONSUMER_GROUP, List.of()));
        when(streamOperations.read(
                        any(Consumer.class),
                        any(StreamReadOptions.class),
                        any(StreamOffset[].class)))
                .thenReturn(List.of());

        StreamConsumerProperties properties =
                new StreamConsumerProperties(
                        true,
                        Duration.ofMillis(50),
                        10,
                        Duration.ofSeconds(60),
                        3,
                        Duration.ofMillis(50));
        List<StreamConsumerWorker> workers =
                Arrays.stream(ConsumedStream.values())
                        .map(
                                stream ->
                                        new StreamConsumerWorker(
                                                stream,
                                                redisTemplate,
                                                properties,
                                                new TickPayloadParser(),
                                                new SignalPayloadParser(),
                                                mock(StreamObservationHandler.class),
                                                mock(DeadLetterPublisher.class)))
                        .toList();
        runner = new StreamConsumerRunner(redisTemplate, workers);
    }

    @Test
    @DisplayName("기동하면 4스트림 전부에 그룹을 만들고 running 상태가 된다")
    void start_createsGroupsForAllStreams() {
        runner.start();
        try {
            assertThat(runner.isRunning()).isTrue();
            for (ConsumedStream stream : ConsumedStream.values()) {
                verify(streamOperations)
                        .createGroup(eq(stream.getKey()), any(), eq(ConsumedStream.CONSUMER_GROUP));
            }
        } finally {
            runner.stop();
        }
    }

    @Test
    @DisplayName("AC-4 — 종료가 생명주기 상한(30s)보다 훨씬 이르게 끝나고 running이 해제된다")
    void stop_completesWellWithinShutdownBudget() {
        runner.start();

        long startedAt = System.nanoTime();
        runner.stop();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(runner.isRunning()).isFalse();
        assertThat(elapsed)
                .as("spring.lifecycle.timeout-per-shutdown-phase(30s) 안에 끝나야 한다")
                .isLessThan(Duration.ofSeconds(15));
    }

    @Test
    @DisplayName("중복 기동·중복 종료가 부작용을 내지 않는다 (Spring 생명주기 재진입 방어)")
    void startAndStop_areIdempotent() {
        runner.start();
        runner.start();
        assertThat(runner.isRunning()).isTrue();

        runner.stop();
        runner.stop();
        assertThat(runner.isRunning()).isFalse();
    }
}
