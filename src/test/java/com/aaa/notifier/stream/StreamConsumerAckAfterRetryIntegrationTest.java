package com.aaa.notifier.stream;

import static com.aaa.notifier.stream.StreamTestSupport.dlqSize;
import static com.aaa.notifier.stream.StreamTestSupport.pendingCount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 재전달-성공 종단 통합 테스트 (REQ-NOTIFIER-CONSUMER-011/051, AC-7).
 *
 * <p>AC-7("확인응답은 처리 성공 이후에만")은 단위 테스트({@code StreamConsumerWorkerTest}의 {@code
 * AcknowledgeOnlyAfterSuccess})가 mock 호출 순서로만 검증했다. 이 테스트는 실 Redis에서 유휴 임계를 짧게 줄여(2s) 그 경로를 실제로
 * 태운다: 하류가 <b>최초 1회만 실패</b>하고 재소유·재전달된 2회차에는 정상 반환하면 — ① 1회차 직후 원본 메시지가 PEL(미확인 목록)에 남고, ② 2회차 이후
 * PEL에서 사라지며(확인응답됨), ③ 하류 시도 횟수는 정확히 2에서 안정되고(3회째 재전달이 없다 — 확인응답이 재소유보다 먼저 반영됐다는 뜻이다), ④ DLQ에는 결코
 * 도달하지 않는다.
 *
 * <p>재소유 경로 자체({@code XPENDING} 판독 → {@code XCLAIM} 재할당)의 4회 이관 시나리오는 {@link
 * StreamConsumerReclaimIntegrationTest}(AC-5·AC-9)가 이미 실 Redis로 검증한다. 이 테스트는 그 자매 시나리오 — "재전달 뒤
 * 성공하면 DLQ에 가지 않고 확인응답된다" — 를 다룬다.
 */
@SpringBootTest(
        properties = {
            "notifier.stream.claim-idle-threshold=2s",
            "notifier.stream.max-delivery-count=3",
            "notifier.stream.block-timeout=200ms"
        })
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
@Import(RecordingHandlerConfiguration.class)
// 컨텍스트가 컨테이너보다 오래 살지 않게 한다 — ContainerContextLifecycleGuardTest 참조.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("재전달-성공 통합 테스트 (실 Redis)")
class StreamConsumerAckAfterRetryIntegrationTest {

    @Container @ServiceConnection
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final String RETRY_SYMBOL = "RETRY";

    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private RecordingStreamHandler handler;

    @Test
    @DisplayName("AC-7 — 1회차 실패 후 미확인으로 남고, 재소유된 2회차 성공 후 확인응답되며 DLQ로는 가지 않는다")
    void firstAttemptFails_secondAttemptSucceeds_acknowledgedWithoutReachingDlq() {
        // Arrange
        handler.failFirstSignalAttemptOf(RETRY_SYMBOL);
        Map<String, String> retrySignal =
                KisFrameFixtures.replace(KisFrameFixtures.signalEntry(), "symbol", RETRY_SYMBOL);

        // Act — 1회차: 발행 직후 하류가 예외를 던져 확인응답되지 않는다.
        StreamTestSupport.publish(redisTemplate, ConsumedStream.SIGNAL_DOMESTIC, retrySignal);
        await().atMost(WAIT).until(() -> handler.signalAttempts() >= 1);

        // Assert (AC-7 ①) — 1회차 직후 원본 메시지는 미확인 상태로 PEL에 남는다.
        // (유휴 임계를 2s로 늘려 뒀으므로 재소유가 시작되기 전 이 구간을 관측할 여유가 있다.)
        assertThat(pendingCount(redisTemplate, ConsumedStream.SIGNAL_DOMESTIC))
                .as("① 1회차 직후 미확인 상태로 남는다")
                .isEqualTo(1);

        // Act — 2회차: 유휴 임계 경과 후 재소유되어 재전달되면 이번엔 정상 처리된다.
        await().atMost(WAIT).until(() -> handler.signalAttempts() >= 2);

        // Assert (AC-7 ②) — 2회차 처리 후 원본이 확인응답되어 PEL에서 사라진다.
        await().atMost(WAIT)
                .until(() -> pendingCount(redisTemplate, ConsumedStream.SIGNAL_DOMESTIC) == 0);

        // Assert (AC-7 ③, 하류 전달이 XACK보다 먼저 — 성공 후 더 이상 재전달되지 않아 시도 횟수가 2로 고정됨이 그 증거다)
        assertThat(handler.signalAttempts()).isEqualTo(2);
        await().atMost(WAIT)
                .during(Duration.ofMillis(800))
                .until(() -> handler.signalAttempts() == 2);

        // Assert — 결국 성공했으므로 DLQ에는 도달하지 않는다.
        assertThat(dlqSize(redisTemplate, ConsumedStream.SIGNAL_DOMESTIC)).isZero();

        // Assert — 2회차에 정상 처리된 신호가 실제로 하류 로그에 도달했다.
        assertThat(handler.signals())
                .extracting(SignalObservation::symbol)
                .containsExactly(RETRY_SYMBOL);
    }
}
