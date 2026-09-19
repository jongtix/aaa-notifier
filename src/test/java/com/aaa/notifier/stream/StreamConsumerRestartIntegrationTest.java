package com.aaa.notifier.stream;

import static com.aaa.notifier.stream.StreamTestSupport.pendingCount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 소비 단위 재기동 오프셋 의미론 통합 테스트 (REQ-NOTIFIER-CONSUMER-002, AC-2 ②③).
 *
 * <p>{@link StreamConsumerIntegrationTest}는 4스트림이 가동 중인 공용 컨텍스트에서 소비 경로를 확인한다. 이 클래스는 <b>그룹을 파괴하고
 * 소비 단위를 재기동하는</b> 시나리오를 다루므로 별도 컨테이너·컨텍스트를 쓰고, 매 테스트 전에 Redis를 비우고 소비 단위를 재기동해 빈 상태에서 시작한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
@Import(RecordingHandlerConfiguration.class)
// 컨텍스트가 컨테이너보다 오래 살지 않게 한다 — ContainerContextLifecycleGuardTest 참조.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("소비 단위 재기동 오프셋 통합 테스트 (실 Redis)")
class StreamConsumerRestartIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    private static final Duration WAIT = Duration.ofSeconds(15);

    /** 부정 단언(일어나지 않아야 하는 일)을 지켜보는 창. */
    private static final Duration QUIET_WINDOW = Duration.ofMillis(500);

    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private RecordingStreamHandler handler;
    @Autowired private StreamConsumerRunner runner;

    /** 소비 단위를 세우고 Redis를 비운 뒤 다시 기동한다 — 그룹이 빈 상태에서 새로 만들어진다. */
    @BeforeEach
    void startFromCleanRedis() {
        runner.stop();
        try (RedisConnection connection =
                Objects.requireNonNull(redisTemplate.getConnectionFactory()).getConnection()) {
            connection.serverCommands().flushAll();
        }
        runner.start();
        handler.clear();
    }

    @Test
    @DisplayName("AC-2 ② — 재기동 뒤 기확인 메시지는 XREADGROUP '>'에 나타나지 않고 다시 전달되지 않는다")
    void restart_doesNotRedeliverAcknowledgedMessages() {
        // Arrange — 1건을 소비·확인응답까지 마친다.
        String acknowledgedId =
                StreamTestSupport.publish(
                        redisTemplate, ConsumedStream.SIGNAL_DOMESTIC, signalOf("FIRST"));
        await().atMost(WAIT)
                .until(
                        () ->
                                handler.signals().size() == 1
                                        && pendingCount(
                                                        redisTemplate,
                                                        ConsumedStream.SIGNAL_DOMESTIC)
                                                == 0);

        // Act — 재기동하되 워커가 끼어들지 못하게 곧바로 다시 멈추고, 탐침 컨슈머로 '>'를 직접 읽는다.
        runner.stop();
        runner.start();
        runner.stop();
        List<MapRecord<String, String, String>> unseen =
                redisTemplate
                        .<String, String>opsForStream()
                        .read(
                                Consumer.from(ConsumedStream.CONSUMER_GROUP, "probe"),
                                StreamReadOptions.empty().count(10),
                                StreamOffset.create(
                                        ConsumedStream.SIGNAL_DOMESTIC.getKey(),
                                        ReadOffset.lastConsumed()));

        // Assert — 단언 ②: 기확인 ID가 '>' 결과에 없다.
        assertThat(unseen)
                .extracting(record -> record.getId().getValue())
                .doesNotContain(acknowledgedId);

        // 재기동 뒤에도 소비는 이어지고, 기확인 메시지는 하류에 다시 가지 않는다(정확히 2건: FIRST 1회 + SECOND 1회).
        runner.start();
        StreamTestSupport.publish(
                redisTemplate, ConsumedStream.SIGNAL_DOMESTIC, signalOf("SECOND"));
        await().atMost(WAIT).until(() -> handler.signals().size() >= 2);
        await().atMost(WAIT).during(QUIET_WINDOW).until(() -> handler.signals().size() == 2);
        assertThat(handler.signals())
                .extracting(SignalObservation::symbol)
                .containsExactly("FIRST", "SECOND");
    }

    @Test
    @DisplayName("AC-2 ③ — 그룹 생성 이전에 쌓인 미소비 과거 메시지는 전달되지 않는다 ($ 정책)")
    void messagesOlderThanGroupCreation_areNotDelivered() {
        // Arrange — 그룹을 없애고, 그룹이 없는 동안 과거 메시지를 쌓는다.
        runner.stop();
        redisTemplate
                .opsForStream()
                .destroyGroup(
                        ConsumedStream.SIGNAL_DOMESTIC.getKey(), ConsumedStream.CONSUMER_GROUP);
        StreamTestSupport.publish(redisTemplate, ConsumedStream.SIGNAL_DOMESTIC, signalOf("OLD"));

        // Act — 기동이 그룹을 스트림 말미($) 기준으로 다시 만든다. 그 뒤에 새 메시지를 발행한다.
        runner.start();
        StreamTestSupport.publish(redisTemplate, ConsumedStream.SIGNAL_DOMESTIC, signalOf("NEW"));

        // Assert — 새 메시지만 도착하고 과거 메시지는 끝내 오지 않는다.
        await().atMost(WAIT).until(() -> !handler.signals().isEmpty());
        await().atMost(WAIT).during(QUIET_WINDOW).until(() -> handler.signals().size() == 1);
        assertThat(handler.signals()).extracting(SignalObservation::symbol).containsExactly("NEW");
    }

    /** 실패 주입 없이 심볼만 다른 신호 엔트리 — 전달 순서·중복을 심볼로 식별한다. */
    private static Map<String, String> signalOf(String symbol) {
        return KisFrameFixtures.replace(KisFrameFixtures.signalEntry(), "symbol", symbol);
    }
}
