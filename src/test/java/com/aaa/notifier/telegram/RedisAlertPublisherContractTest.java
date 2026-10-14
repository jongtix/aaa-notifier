package com.aaa.notifier.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@code stream:alert} 엔트리 필드 계약 (SPEC-INFRA-BOOT4-001 REQ-BOOT4-015, AC-BOOT4-015).
 *
 * <p>운영 {@link RedisAlertPublisher}가 XADD하는 필드 맵을 Boot 3.5.16 기준 골든으로 고정한다 — 필드 이름 집합과 각 값의 문자열 표현이
 * Boot 전환 전후 같아야 한다. 스트림 필드 맵은 JSON이 아니므로 값 문자열을 그대로 비교한다.
 */
@DisplayName("RedisAlertPublisher — stream:alert 필드 맵 계약 (Boot 3.5 기준 골든)")
class RedisAlertPublisherContractTest {

    private static final OffsetDateTime SENT_AT =
            OffsetDateTime.of(2026, 9, 29, 22, 31, 5, 123_000_000, ZoneOffset.ofHours(9));

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final StreamOperations<String, Object, Object> streamOperations =
            mock(StreamOperations.class);

    private RedisAlertPublisher publisher;

    @BeforeEach
    void setUp() {
        when(redis.opsForStream()).thenReturn(streamOperations);
        publisher =
                new RedisAlertPublisher(
                        redis, 500, TelegramMetrics.create(new SimpleMeterRegistry()));
    }

    @SuppressWarnings("unchecked")
    private MapRecord<String, String, String> captured() {
        ArgumentCaptor<MapRecord<String, String, String>> record =
                ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOperations).add(record.capture(), any());
        return record.getValue();
    }

    @Test
    @DisplayName("score·confidence·메시지 ID가 있는 결정 — 13개 필드 이름과 값 문자열이 골든과 같다")
    void fullDecisionFieldMap() {
        publisher.publish(TelegramAlerts.acFive(), 4242L, SENT_AT);

        MapRecord<String, String, String> record = captured();

        assertThat(record.getStream()).isEqualTo("stream:alert");
        assertThat(record.getValue())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.ofEntries(
                                Map.entry("tier", "1"),
                                Map.entry("stock_id", "7"),
                                Map.entry("symbol", "BRK.B"),
                                Map.entry("market", "OVERSEAS"),
                                Map.entry("horizon", "D20"),
                                Map.entry("signal", "STRONG_BUY"),
                                Map.entry("score", "-0.043"),
                                Map.entry("confidence", "0.612"),
                                Map.entry("trade_date", "2026-09-29"),
                                Map.entry("notification_type", "TIMING"),
                                Map.entry("trace_id", "trace-ac05"),
                                Map.entry("sent_at", "2026-09-29T22:31:05.123+09:00"),
                                Map.entry("telegram_message_id", "4242")));
    }

    @Test
    @DisplayName("score·confidence·메시지 ID가 없는 결정 — null은 빈 문자열로 실린다")
    void nullValuesAreEmptyStrings() {
        publisher.publish(TelegramAlerts.withoutSignal("trace-null-01"), null, SENT_AT);

        assertThat(captured().getValue())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.ofEntries(
                                Map.entry("tier", "2"),
                                Map.entry("stock_id", "1"),
                                Map.entry("symbol", "005930"),
                                Map.entry("market", "DOMESTIC"),
                                Map.entry("horizon", "D20"),
                                Map.entry("signal", "BUY"),
                                Map.entry("score", ""),
                                Map.entry("confidence", ""),
                                Map.entry("trade_date", "2026-09-29"),
                                Map.entry("notification_type", "TIMING"),
                                Map.entry("trace_id", "trace-null-01"),
                                Map.entry("sent_at", "2026-09-29T22:31:05.123+09:00"),
                                Map.entry("telegram_message_id", "")));
    }
}
