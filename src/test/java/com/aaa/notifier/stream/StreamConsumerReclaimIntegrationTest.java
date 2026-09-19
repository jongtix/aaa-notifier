package com.aaa.notifier.stream;

import static com.aaa.notifier.stream.StreamTestSupport.dlqSize;
import static com.aaa.notifier.stream.StreamTestSupport.pendingCount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 재소유·DLQ 이관 종단 통합 테스트 (REQ-NOTIFIER-CONSUMER-011/021, AC-5 경계 3/4, AC-9).
 *
 * <p>재소유 경로({@code XPENDING} 판독 → {@code XCLAIM} 재할당)는 단위 테스트에서 mock으로만 호출 순서를 고정했다. 이 테스트는 실
 * Redis에서 유휴 임계를 짧게 줄여(300ms) 그 경로를 실제로 태운다: 항상 실패하는 메시지가 <b>정확히 4번</b> 전달(최초 1 + 재소유 3)된 뒤 임계
 * 초과(재전달 4회)로 DLQ에 나타나야 한다. 3회째는 재소유되어 처리되고 4회째부터 이관되므로, 판독이 재할당보다 뒤에 있었다면 횟수가 한 회차씩 밀려 4가 아닌 다른 값이
 * 된다.
 */
@SpringBootTest(
        properties = {
            "notifier.stream.claim-idle-threshold=300ms",
            "notifier.stream.max-delivery-count=3",
            "notifier.stream.block-timeout=200ms"
        })
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
@Import(RecordingHandlerConfiguration.class)
// 컨텍스트가 컨테이너보다 오래 살지 않게 한다 — ContainerContextLifecycleGuardTest 참조.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("재소유·DLQ 이관 통합 테스트 (실 Redis)")
class StreamConsumerReclaimIntegrationTest {

    @Container @ServiceConnection
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final String POISON_SYMBOL = "POISON";

    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private RecordingStreamHandler handler;

    @Test
    @DisplayName("AC-5·AC-9 — 항상 실패하는 메시지는 정확히 4번 전달된 뒤 DLQ로 이관되고 원본은 확인응답된다")
    void alwaysFailingMessage_isDeliveredFourTimes_thenMovedToDlq() {
        // Arrange
        handler.failSignalsOf(POISON_SYMBOL);
        Map<String, String> poison =
                KisFrameFixtures.replace(KisFrameFixtures.signalEntry(), "symbol", POISON_SYMBOL);

        // Act
        String originalId =
                StreamTestSupport.publish(redisTemplate, ConsumedStream.SIGNAL_DOMESTIC, poison);
        await().atMost(WAIT)
                .until(() -> dlqSize(redisTemplate, ConsumedStream.SIGNAL_DOMESTIC) >= 1);

        // Assert (AC-5) — 재전달 3회째는 재소유되어 처리되고 4회째부터 이관된다: 하류 시도는 정확히 4번,
        // 이관 뒤에는 더 늘지 않는다.
        assertThat(handler.signalAttempts()).isEqualTo(4);
        await().atMost(WAIT)
                .during(Duration.ofMillis(800))
                .until(() -> handler.signalAttempts() == 4);

        // Assert (AC-9)
        List<MapRecord<String, String, String>> dlq =
                redisTemplate
                        .<String, String>opsForStream()
                        .range(ConsumedStream.SIGNAL_DOMESTIC.dlqKey(), Range.unbounded());
        assertThat(dlq).hasSize(1);
        assertThat(dlq.getFirst().getValue())
                .as("① 원본 필드 전부 보존 + ②③④ 이관 메타데이터")
                .containsAllEntriesOf(poison)
                .containsEntry("original_id", originalId)
                .containsEntry("original_stream", "stream:signal:domestic")
                .containsEntry("reason", "max_delivery_exceeded");
        assertThat(pendingCount(redisTemplate, ConsumedStream.SIGNAL_DOMESTIC))
                .as("⑤ 원본 미확인 건수")
                .isZero();
    }
}
