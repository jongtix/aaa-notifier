package com.aaa.notifier.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.notifier.telegram.SafeModeStore.AutoEntry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * safe_mode 조건부 쓰기·대기 큐·{@code stream:alert}를 실제 Redis로 검증한다 (REQ-031·032·033·041, design.md
 * §2.2~§2.3·§5).
 *
 * <p>조건부 쓰기의 원자성(서버 측 스크립트)과 근사 MAXLEN 트리밍은 Redis 실물로만 확인된다. Spring 컨텍스트 없이 템플릿만 만든다.
 */
@Testcontainers
@Tag("integration")
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("텔레그램 Redis 어댑터 — safe_mode CAS·대기 큐·stream:alert (실 Redis)")
class TelegramRedisAdaptersIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private SimpleMeterRegistry registry;
    private TelegramMetrics metrics;

    @BeforeAll
    static void connect() {
        connectionFactory =
                new LettuceConnectionFactory(
                        new RedisStandaloneConfiguration(
                                REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        redis.delete(
                List.of(RedisSafeModeStore.KEY, RedisPendingQueue.KEY, RedisAlertPublisher.KEY));
        registry = new SimpleMeterRegistry();
        metrics = TelegramMetrics.create(registry);
    }

    @Nested
    @DisplayName("safe_mode 저장소")
    class SafeMode {

        private RedisSafeModeStore store;

        @BeforeEach
        void setUp() {
            store = new RedisSafeModeStore(redis, metrics);
        }

        @Test
        @DisplayName("키가 없으면 OFF로 읽고, 읽은 값을 캐시와 게이지에 반영한다")
        void read_missingKey() {
            SafeModeReading reading = store.read();

            assertThat(reading.state()).isEqualTo(SafeModeState.OFF);
            assertThat(reading.raw()).isNull();
            assertThat(store.lastObserved()).isEqualTo(SafeModeState.OFF);
        }

        @Test
        @DisplayName("AC-11 ⑤ — 미인식 값은 ON으로 읽고 원래 값을 담은 WARN 로그를 남긴다")
        void read_unknownValue_warns(CapturedOutput output) {
            redis.opsForValue().set(RedisSafeModeStore.KEY, "true");

            assertThat(store.read().state()).isEqualTo(SafeModeState.ON);
            assertThat(output.getAll()).contains("WARN").contains("raw=true");
            assertThat(
                            registry.get("notifier.telegram.safe.mode")
                                    .tag("source", "operator")
                                    .gauge()
                                    .value())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("T1 — 키가 없거나 OFF(공백·대소문자 무관)일 때만 AUTO를 기록한다")
        void enterAuto_fromOff() {
            assertThat(store.enterAuto()).isEqualTo(AutoEntry.WRITTEN);
            assertThat(redis.opsForValue().get(RedisSafeModeStore.KEY)).isEqualTo("AUTO");

            redis.opsForValue().set(RedisSafeModeStore.KEY, " off ");
            assertThat(store.enterAuto()).isEqualTo(AutoEntry.WRITTEN);
            assertThat(redis.opsForValue().get(RedisSafeModeStore.KEY)).isEqualTo("AUTO");
        }

        @Test
        @DisplayName("AC-11 ④ / R-A — 운영자 값(ON·미인식)이면 AUTO로 덮어쓰지 않는다")
        void enterAuto_neverOverwritesOperatorValue() {
            redis.opsForValue().set(RedisSafeModeStore.KEY, "ON");
            assertThat(store.enterAuto()).isEqualTo(AutoEntry.REJECTED);
            assertThat(redis.opsForValue().get(RedisSafeModeStore.KEY)).isEqualTo("ON");

            redis.opsForValue().set(RedisSafeModeStore.KEY, "1");
            assertThat(store.enterAuto()).isEqualTo(AutoEntry.REJECTED);
            assertThat(redis.opsForValue().get(RedisSafeModeStore.KEY)).isEqualTo("1");
        }

        @Test
        @DisplayName("T2 — 읽은 값이 여전히 AUTO면 OFF로 해제한다")
        void releaseAuto_whenStillAuto() {
            redis.opsForValue().set(RedisSafeModeStore.KEY, "auto");
            SafeModeReading reading = store.read();

            assertThat(store.releaseAuto(reading)).isTrue();
            assertThat(redis.opsForValue().get(RedisSafeModeStore.KEY)).isEqualTo("OFF");
            assertThat(store.lastObserved()).isEqualTo(SafeModeState.OFF);
        }

        @Test
        @DisplayName("AC-12 ④ / R-B — 읽은 뒤 운영자가 ON으로 바꿨으면 해제하지 않는다")
        void releaseAuto_operatorChangedMeanwhile() {
            redis.opsForValue().set(RedisSafeModeStore.KEY, "AUTO");
            SafeModeReading reading = store.read();
            redis.opsForValue().set(RedisSafeModeStore.KEY, "ON");

            assertThat(store.releaseAuto(reading)).isFalse();
            assertThat(redis.opsForValue().get(RedisSafeModeStore.KEY)).isEqualTo("ON");
        }

        @Test
        @DisplayName("조건부 쓰기 — 기대한 원래 값(키 없음 포함)과 다르면 쓰지 않는다")
        void compareAndSet_primitive() {
            assertThat(store.compareAndSet(null, "AUTO")).isTrue();
            assertThat(store.compareAndSet(null, "OFF")).as("키가 이미 있다").isFalse();
            assertThat(store.compareAndSet("OFF", "ON")).as("현재 값은 AUTO").isFalse();
            assertThat(store.compareAndSet("AUTO", "OFF")).isTrue();
            assertThat(redis.opsForValue().get(RedisSafeModeStore.KEY)).isEqualTo("OFF");
        }
    }

    @Nested
    @DisplayName("대기 큐 queue:telegram:pending")
    class PendingList {

        private RedisPendingQueue queue;

        @BeforeEach
        void setUp() {
            queue = new RedisPendingQueue(redis, Duration.ofHours(24));
        }

        private QueuedAlert alert(String traceId, LocalDateTime queuedAt) {
            return QueuedAlert.of(
                    TelegramAlerts.candidate(traceId), queuedAt, DivertReason.TRANSIENT_EXHAUSTED);
        }

        @Test
        @DisplayName("뒤에 적재하고 FIFO로 읽으며, 항목에 알림 표시 정보와 trace_id를 담는다 (AC-09 ③)")
        void appendAndSnapshot_fifo() {
            LocalDateTime t0 = LocalDateTime.of(2026, 9, 29, 10, 0);
            queue.append(alert("trace-1", t0));
            queue.append(alert("trace-2", t0.plusMinutes(1)));

            QueueSnapshot snapshot = queue.snapshot();

            assertThat(snapshot.rawCount()).isEqualTo(2);
            assertThat(snapshot.alerts())
                    .extracting(QueuedAlert::traceId)
                    .containsExactly("trace-1", "trace-2");
            assertThat(snapshot.alerts().getFirst())
                    .extracting(
                            QueuedAlert::symbol,
                            QueuedAlert::horizon,
                            QueuedAlert::to,
                            QueuedAlert::queuedAt)
                    .containsExactly("005930", "D20", "BUY", t0);
            assertThat(snapshot.alerts().getFirst().score())
                    .isEqualByComparingTo(new BigDecimal("0.043"));
        }

        @Test
        @DisplayName("키에 보존 기간 백스톱 TTL을 건다 (plan.md §D.7)")
        void append_setsBackstopTtl() {
            queue.append(alert("trace-1", LocalDateTime.of(2026, 9, 29, 10, 0)));

            Long ttl = redis.getExpire(RedisPendingQueue.KEY);
            assertThat(ttl).isPositive().isLessThanOrEqualTo(Duration.ofHours(24).toSeconds());
        }

        @Test
        @DisplayName("앞에서 읽은 개수만큼만 잘라내 — 그 사이 뒤에 붙은 항목은 남는다 (design.md §5)")
        void removeFirst_keepsLaterAppends() {
            LocalDateTime t0 = LocalDateTime.of(2026, 9, 29, 10, 0);
            queue.append(alert("trace-1", t0));
            QueueSnapshot snapshot = queue.snapshot();
            queue.append(alert("trace-2", t0));

            queue.removeFirst(snapshot.rawCount());

            assertThat(queue.size()).isEqualTo(1);
            assertThat(queue.snapshot().alerts().getFirst().traceId()).isEqualTo("trace-2");
        }

        @Test
        @DisplayName("해석할 수 없는 항목은 목록에서 빠지지만 읽은 개수에는 들어간다")
        void unparseableEntry_countedButNotListed() {
            queue.append(alert("trace-1", LocalDateTime.of(2026, 9, 29, 10, 0)));
            redis.opsForList().rightPush(RedisPendingQueue.KEY, "garbage");

            QueueSnapshot snapshot = queue.snapshot();

            assertThat(snapshot.rawCount()).isEqualTo(2);
            assertThat(snapshot.alerts()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("stream:alert 발행 (REQ-041, AC-14)")
    class AlertStream {

        private RedisAlertPublisher publisher;

        @BeforeEach
        void setUp() {
            publisher = new RedisAlertPublisher(redis, 500, metrics);
        }

        private List<MapRecord<String, Object, Object>> entries() {
            return redis.opsForStream().range(RedisAlertPublisher.KEY, Range.unbounded());
        }

        @Test
        @DisplayName("AC-14 ① ② — 필드 집합이 계약과 정확히 같고 값은 전부 문자열, null은 빈 문자열, sent_at은 KST ISO-8601")
        void publish_fieldContract() {
            OffsetDateTime sentAt =
                    OffsetDateTime.of(2026, 9, 29, 10, 0, 5, 0, ZoneOffset.ofHours(9));

            publisher.publish(TelegramAlerts.withoutSignal("trace-x"), 777L, sentAt);

            Map<Object, Object> fields = entries().getFirst().getValue();
            assertThat(fields)
                    .containsOnlyKeys(
                            "tier",
                            "stock_id",
                            "symbol",
                            "market",
                            "horizon",
                            "signal",
                            "score",
                            "confidence",
                            "trade_date",
                            "notification_type",
                            "trace_id",
                            "sent_at",
                            "telegram_message_id")
                    .containsEntry("score", "")
                    .containsEntry("confidence", "")
                    .containsEntry("signal", "BUY")
                    .containsEntry("notification_type", "TIMING")
                    .containsEntry("trace_id", "trace-x")
                    .containsEntry("telegram_message_id", "777")
                    .containsEntry("sent_at", "2026-09-29T10:00:05+09:00")
                    .containsEntry("trade_date", "2026-09-29");
        }

        @Test
        @DisplayName("AC-14 ③ — 510건 발행 후 길이가 근사 MAXLEN 500 근방이다")
        void publish_approximateTrimming() {
            OffsetDateTime sentAt =
                    OffsetDateTime.of(2026, 9, 29, 10, 0, 5, 0, ZoneOffset.ofHours(9));
            for (int i = 0; i < 510; i++) {
                publisher.publish(TelegramAlerts.candidate("trace-" + i), (long) i, sentAt);
            }

            assertThat(redis.opsForStream().size(RedisAlertPublisher.KEY)).isBetween(500L, 600L);
        }
    }
}
