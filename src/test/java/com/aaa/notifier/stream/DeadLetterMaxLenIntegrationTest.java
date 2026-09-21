package com.aaa.notifier.stream;

import static com.aaa.notifier.stream.StreamTestSupport.dlqSize;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * DLQ 상한 통합 테스트 (TECHSPEC §5.1 — {@code stream:dlq:{stream명}} MAXLEN 500, 정확 트리밍).
 *
 * <p>상한을 5로 줄여 실 Redis에서 검증한다. 근사(~) 트리밍은 매크로 노드 단위로만 잘라내므로 상한이 작을 때는 트리밍이 아예 일어나지 않는다 — 그래서 이 테스트는
 * "정확 트리밍"과 "근사 트리밍"을 구분해 낸다. 소비 루프는 이 관심사와 무관하므로 끈다({@code notifier.stream.enabled=false}).
 */
@SpringBootTest(properties = {"notifier.stream.enabled=false", "notifier.stream.dlq-max-len=5"})
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
// 컨텍스트가 컨테이너보다 오래 살지 않게 한다 — ContainerContextLifecycleGuardTest 참조.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("DLQ 상한 통합 테스트 (실 Redis, TECHSPEC §5.1)")
class DeadLetterMaxLenIntegrationTest {

    @Container @ServiceConnection
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    private static final int DLQ_MAX_LEN = 5;

    @Autowired private DeadLetterPublisher publisher;
    @Autowired private StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("상한을 넘겨 이관하면 DLQ 길이가 정확히 상한과 같다 (근사 트리밍이 아니다)")
    void transferringBeyondTheBound_keepsExactlyTheBound() {
        // Arrange
        int total = DLQ_MAX_LEN * 3;

        // Act
        for (int i = 0; i < total; i++) {
            transfer(ConsumedStream.SIGNAL_OVERSEAS, i);
        }

        // Assert
        assertThat(dlqSize(redisTemplate, ConsumedStream.SIGNAL_OVERSEAS)).isEqualTo(DLQ_MAX_LEN);
    }

    @Test
    @DisplayName("트리밍은 가장 오래된 항목부터 밀어내고 최신 상한 건수를 남긴다")
    void trimming_evictsOldest_andRetainsNewest() {
        // Arrange
        int total = DLQ_MAX_LEN + 4;

        // Act
        for (int i = 0; i < total; i++) {
            transfer(ConsumedStream.TICK_OVERSEAS, i);
        }

        // Assert
        List<MapRecord<String, String, String>> retained =
                redisTemplate
                        .<String, String>opsForStream()
                        .range(ConsumedStream.TICK_OVERSEAS.dlqKey(), Range.unbounded());
        assertThat(retained)
                .extracting(record -> record.getValue().get("original_id"))
                .containsExactly(
                        originalId(4), originalId(5), originalId(6), originalId(7), originalId(8));
    }

    @Test
    @DisplayName("상한 이내에서는 아무것도 잘리지 않는다")
    void withinTheBound_nothingIsTrimmed() {
        // Act
        for (int i = 0; i < DLQ_MAX_LEN; i++) {
            transfer(ConsumedStream.SIGNAL_DOMESTIC, i);
        }

        // Assert
        assertThat(dlqSize(redisTemplate, ConsumedStream.SIGNAL_DOMESTIC)).isEqualTo(DLQ_MAX_LEN);
    }

    /** DLQ는 스트림별로 분리되므로 테스트마다 다른 스트림을 써서 서로의 길이에 영향을 주지 않는다. */
    private void transfer(ConsumedStream stream, int sequence) {
        publisher.transfer(
                stream,
                originalId(sequence),
                Map.of("symbol", "005930", "trace_id", KisFrameFixtures.TRACE_ID),
                DeadLetterPublisher.Reason.MAX_DELIVERY_EXCEEDED);
    }

    private static String originalId(int sequence) {
        return "1758100000000-" + sequence;
    }
}
