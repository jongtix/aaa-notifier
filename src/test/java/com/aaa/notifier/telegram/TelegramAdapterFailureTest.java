package com.aaa.notifier.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aaa.notifier.filter.MarketSession;
import com.aaa.notifier.telegram.SafeModeStore.AutoEntry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 저장·발행 어댑터의 장애 경로 — Redis·MySQL 오류가 발송 경로로 번지지 않는다 (plan.md §D.10, REQ-002 기록 fail-open, REQ-041
 * 발행 fail-open).
 */
@DisplayName("텔레그램 어댑터 장애 경로 — fail-open")
class TelegramAdapterFailureTest {

    private SimpleMeterRegistry registry;
    private TelegramMetrics metrics;
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = TelegramMetrics.create(registry);
        redis = mock(StringRedisTemplate.class);
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }

    @Test
    @DisplayName("R-F — safe_mode를 읽지 못하면 OFF로 간주하고 읽기 실패로 표시한다 (plan.md §D.10)")
    @SuppressWarnings("unchecked")
    void safeModeReadFailure_isOff() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(RedisSafeModeStore.KEY))
                .thenThrow(new RedisConnectionFailureException("down"));

        SafeModeReading reading = new RedisSafeModeStore(redis, metrics).read();

        assertThat(reading.state()).isEqualTo(SafeModeState.OFF);
        assertThat(reading.readFailed()).isTrue();
    }

    @Test
    @DisplayName("조건부 쓰기가 Redis 오류로 실패하면 쓰지 않은 것(FAILED)으로 본다")
    @SuppressWarnings("unchecked")
    void safeModeCasFailure_isFailed() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        doThrow(new RedisConnectionFailureException("down"))
                .when(redis)
                .execute(any(RedisScript.class), anyList(), any(), any(), any());

        assertThat(new RedisSafeModeStore(redis, metrics).enterAuto()).isEqualTo(AutoEntry.FAILED);
    }

    @Test
    @DisplayName("AC-14 ④ — stream:alert 발행이 실패해도 예외를 전파하지 않고 발행 실패 계측을 올린다")
    @SuppressWarnings("unchecked")
    void publishFailure_isFailOpen() {
        StreamOperations<String, Object, Object> ops = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(ops);
        when(ops.add(any(MapRecord.class), any()))
                .thenThrow(new RedisConnectionFailureException("down"));
        RedisAlertPublisher publisher = new RedisAlertPublisher(redis, 500, metrics);

        assertThatCode(
                        () ->
                                publisher.publish(
                                        TelegramAlerts.candidate("t1"),
                                        1L,
                                        OffsetDateTime.of(
                                                2026, 9, 29, 10, 0, 0, 0, ZoneOffset.ofHours(9))))
                .doesNotThrowAnyException();
        assertThat(counter("notifier.telegram.alert.publish.failure")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("AC-02 ③ — notification_log INSERT가 실패해도 예외를 전파하지 않고 기록 실패 계측을 올린다")
    void recordFailure_isFailOpen() {
        JdbcTemplate failing =
                new JdbcTemplate() {
                    @Override
                    public int update(String sql, Object... args) {
                        throw new DataAccessResourceFailureException("db down");
                    }
                };
        JdbcNotificationLog notificationLog =
                new JdbcNotificationLog(failing, metrics, Clock.system(MarketSession.KST));

        assertThatCode(
                        () ->
                                notificationLog.alert(
                                        EventType.SENT, TelegramAlerts.candidate("t1"), "b", 1L))
                .doesNotThrowAnyException();
        assertThatCode(() -> notificationLog.summary(EventType.SUMMARY_SENT, "s", "b", 1L))
                .doesNotThrowAnyException();
        assertThat(counter("notifier.telegram.record.failure")).isEqualTo(2.0);
    }
}
