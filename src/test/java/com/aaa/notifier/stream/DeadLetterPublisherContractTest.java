package com.aaa.notifier.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@code stream:dlq:*} 엔트리 필드 계약 (SPEC-INFRA-BOOT4-001 REQ-BOOT4-015, AC-BOOT4-015).
 *
 * <p>운영 {@link DeadLetterPublisher}가 XADD하는 키와 필드 맵 전체를 Boot 3.5.16 기준 골든으로 고정한다 — 원본 필드 보존 + 부가 필드
 * 3개({@code original_id}·{@code original_stream}·{@code reason})의 이름과 값 문자열이 Boot 전환 전후 같아야 한다.
 */
@DisplayName("DeadLetterPublisher — stream:dlq:* 필드 맵 계약 (Boot 3.5 기준 골든)")
class DeadLetterPublisherContractTest {

    private static final String ORIGINAL_ID = "1758100000000-0";

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final StreamOperations<String, String, String> streamOperations =
            mock(StreamOperations.class);

    private DeadLetterPublisher publisher;

    @BeforeEach
    void setUp() {
        when(redis.<String, String>opsForStream()).thenReturn(streamOperations);
        publisher = new DeadLetterPublisher(redis, 500);
    }

    @SuppressWarnings("unchecked")
    private MapRecord<String, String, String> captured() {
        ArgumentCaptor<MapRecord<String, String, String>> record =
                ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOperations).add(record.capture(), any());
        return record.getValue();
    }

    @Test
    @DisplayName("틱 스트림 재전달 초과 — DLQ 키와 필드 맵 전체가 골든과 같다")
    void tickMaxDeliveryExceeded() {
        publisher.transfer(
                ConsumedStream.TICK_DOMESTIC,
                ORIGINAL_ID,
                Map.of(
                        "symbol", "005930",
                        "trId", "H0STCNT0",
                        "data", "005930^151425^71500",
                        "trace_id", "trace-dlq-01"),
                DeadLetterPublisher.Reason.MAX_DELIVERY_EXCEEDED);

        MapRecord<String, String, String> record = captured();

        assertThat(record.getStream()).isEqualTo("stream:dlq:stream:tick:domestic");
        assertThat(record.getValue())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                                "symbol", "005930",
                                "trId", "H0STCNT0",
                                "data", "005930^151425^71500",
                                "trace_id", "trace-dlq-01",
                                "original_id", ORIGINAL_ID,
                                "original_stream", "stream:tick:domestic",
                                "reason", "max_delivery_exceeded"));
    }

    @Test
    @DisplayName("신호 스트림 해석 불가 — 원본과 이름이 겹치는 부가 필드는 부가 값이 이긴다")
    void signalPayloadUnparseable() {
        publisher.transfer(
                ConsumedStream.SIGNAL_OVERSEAS,
                ORIGINAL_ID,
                Map.of("symbol", "BRK.B", "reason", "upstream-value"),
                DeadLetterPublisher.Reason.PAYLOAD_UNPARSEABLE);

        MapRecord<String, String, String> record = captured();

        assertThat(record.getStream()).isEqualTo("stream:dlq:stream:signal:overseas");
        assertThat(record.getValue())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                                "symbol", "BRK.B",
                                "original_id", ORIGINAL_ID,
                                "original_stream", "stream:signal:overseas",
                                "reason", "payload_unparseable"));
    }
}
