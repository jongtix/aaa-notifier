package com.aaa.notifier.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 대기 큐 {@code queue:telegram:pending} JSON 형식 계약 (SPEC-INFRA-BOOT4-001 REQ-BOOT4-011·016,
 * AC-BOOT4-016 (a)).
 *
 * <p>픽스처 {@code contract/pending-queue-entry-boot35.json}은 Spring Boot 3.5.16(Jackson 2) 빌드의 운영
 * {@link RedisPendingQueue}가 쓴 형식이다. Boot 전환 전후 모두 ① 그 형식을 같은 값으로 읽고 ② 같은 입력을 JSON 의미상 같은 형식으로 써야
 * 한다 — 배포·롤백 시점에 큐에 남은 항목을 양쪽 버전이 서로 읽을 수 있다는 근거다.
 *
 * <p>Jackson 타입을 쓰지 않는다(acceptance.md §A.1) — 비교는 운영 클래스가 읽어 낸 값과 JSONassert 의미 비교로만 한다.
 */
@DisplayName("RedisPendingQueue — queue:telegram:pending JSON 형식 계약 (Boot 3.5 기준 골든)")
class RedisPendingQueueFormatContractTest {

    private static final String FIXTURE_PATH = "/contract/pending-queue-entry-boot35.json";

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ListOperations<String, String> listOperations = mock(ListOperations.class);

    private RedisPendingQueue queue;

    @BeforeEach
    void setUp() {
        when(redis.opsForList()).thenReturn(listOperations);
        queue = new RedisPendingQueue(redis, Duration.ofHours(24));
    }

    /** 픽스처와 같은 값을 갖는 항목 — score는 있고 confidence는 없다(null 멤버 표현까지 고정). */
    private static QueuedAlert expectedAlert() {
        return new QueuedAlert(
                LocalDateTime.of(2026, 9, 29, 10, 15, 30, 123_456_000),
                "trace-pending-01",
                7L,
                "BRK.B",
                "OVERSEAS",
                "D20",
                "BUY",
                "STRONG_BUY",
                1,
                new BigDecimal("-0.043"),
                null,
                LocalDate.of(2026, 9, 29),
                "safe_mode");
    }

    private static String fixture() throws IOException {
        try (InputStream in =
                RedisPendingQueueFormatContractTest.class.getResourceAsStream(FIXTURE_PATH)) {
            assertThat(in).as("픽스처 %s", FIXTURE_PATH).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    @Test
    @DisplayName("Boot 3.5가 쓴 항목을 같은 값으로 읽는다 (해석 불가 0건)")
    void readsBoot35Entry() throws IOException {
        when(listOperations.range(RedisPendingQueue.KEY, 0, -1)).thenReturn(List.of(fixture()));

        QueueSnapshot snapshot = queue.snapshot();

        assertThat(snapshot.rawCount()).isEqualTo(1);
        assertThat(snapshot.alerts()).containsExactly(expectedAlert());
    }

    @Test
    @DisplayName("같은 입력을 Boot 3.5 형식과 JSON 의미상 같게 쓴다 (멤버 이름·값 표현·null 멤버 포함)")
    void writesBoot35Format() throws Exception {
        queue.append(expectedAlert());

        // Arrange
        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
        verify(listOperations).rightPush(anyString(), written.capture());

        // Assert
        JSONAssert.assertEquals(fixture(), written.getValue(), JSONCompareMode.STRICT);
    }
}
