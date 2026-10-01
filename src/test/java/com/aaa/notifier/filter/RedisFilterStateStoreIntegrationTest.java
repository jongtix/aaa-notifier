package com.aaa.notifier.filter;

import static com.aaa.notifier.filter.FilterTestFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Redis 상태 저장소 통합 테스트 (design.md §2, AC-17) — 키 패턴·만료·리스트 트리밍을 실 Redis에서 확인한다.
 *
 * <p>Spring 컨텍스트 없이 저장소만 직접 조립한다 — 검증 대상이 Redis 명령의 실제 효과(TTL 유무, {@code LTRIM} 결과)라서다.
 */
@Testcontainers
@Tag("integration")
@DisplayName("Redis 필터 상태 저장소 통합 테스트 (실 Redis)")
class RedisFilterStateStoreIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private RedisFilterStateStore store;

    @BeforeAll
    static void connect() {
        connectionFactory =
                new LettuceConnectionFactory(
                        new RedisStandaloneConfiguration(
                                REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        redisTemplate.execute(
                connection -> {
                    connection.serverCommands().flushAll();
                    return null;
                },
                true);
        store = new RedisFilterStateStore(redisTemplate);
    }

    @Test
    @DisplayName("AC-17 — filter:confidence는 만료 없는 리스트이며 6번째 값에서 가장 오래된 값이 트리밍된다")
    void confidenceWindow_hasNoExpiryAndTrimsOldest() {
        // Act
        Stream.of("0.60", "0.61", "0.62", "0.63", "0.64", "0.65")
                .map(BigDecimal::new)
                .forEach(value -> store.appendConfidence("005930", "D20", value, 5));

        // Assert
        String key = FilterKeys.confidence("005930", "D20");
        assertThat(redisTemplate.getExpire(key)).as("다일 수명 — EXPIREAT 없음").isEqualTo(-1L);
        assertThat(redisTemplate.opsForList().range(key, 0, -1))
                .containsExactly("0.61", "0.62", "0.63", "0.64", "0.65");
        assertThat(store.confidences("005930", "D20")).hasSize(5).last().isEqualTo(bd("0.65"));
    }

    @Test
    @DisplayName("filter:signal은 만료 없는 해시 스냅샷으로 덮어쓰인다")
    void signalSnapshot_roundTripsWithoutExpiry() {
        // Arrange
        SignalSnapshot snapshot =
                new SignalSnapshot("BUY", bd("0.043"), bd("0.712"), LocalDate.of(2026, 9, 28), "t");

        // Act
        store.saveSignal("005930", "D20", snapshot);

        // Assert
        assertThat(store.signal("005930", "D20")).contains(snapshot);
        assertThat(redisTemplate.getExpire(FilterKeys.signal("005930", "D20"))).isEqualTo(-1L);
    }

    @Test
    @DisplayName("filter:confirm 후보는 장 마감 TTL과 함께 저장·복원·삭제된다")
    void pendingTransition_roundTrips() {
        // Arrange
        PendingTransition pending =
                PendingTransition.started(
                        Grade.HOLD, Grade.BUY, Instant.parse("2026-09-29T01:00:00Z"));

        // Act
        store.savePending("005930", "D20", pending, Duration.ofMinutes(30));

        // Assert
        assertThat(store.pending("005930", "D20")).contains(pending);
        assertThat(redisTemplate.getExpire(FilterKeys.confirm("005930", "D20")))
                .isBetween(1_700L, 1_800L);
        store.clearPending("005930", "D20");
        assertThat(store.pending("005930", "D20")).isEmpty();
    }

    @Test
    @DisplayName(
            "FILTER-002 AC-16 — 배포 전 형식의 후보(since 없음, 폐기된 count·last_reason=CONFIRM_PENDING)는 예외 없이 since 없이 복원된다 (REQ-011)")
    void legacyPendingWithoutSince_restoresWithNullSince() {
        // Arrange — 배포 전 형식의 Hash(전환 체결 시각 없음)
        redisTemplate
                .opsForHash()
                .putAll(
                        FilterKeys.confirm("005930", "D20"),
                        Map.of(
                                "from",
                                "HOLD",
                                "to",
                                "BUY",
                                "count",
                                "3",
                                "last_reason",
                                "CONFIRM_PENDING"));

        // Act
        PendingTransition restored = store.pending("005930", "D20").orElseThrow();

        // Assert
        assertThat(restored.since()).isNull();
        assertThat(restored.to()).isEqualTo(Grade.BUY);
    }

    @Test
    @DisplayName(
            "FILTER-002 — filter:recorded는 장 마감 TTL Set이며 새 멤버일 때만 true, 지정한 멤버만 지운다 (REQ-014)")
    void recordedSet_addsOnceAndRemovesOnlyGivenMembers() {
        // Arrange
        String holdBuy =
                FilterKeys.recordedMember(Grade.HOLD, Grade.BUY, SuppressionReason.DWELL_PENDING);
        String holdSell =
                FilterKeys.recordedMember(Grade.HOLD, Grade.SELL, SuppressionReason.DWELL_PENDING);

        // Act
        boolean first = store.markRecorded("005930", "D20", holdBuy, Duration.ofMinutes(30));
        boolean second = store.markRecorded("005930", "D20", holdBuy, Duration.ofMinutes(30));
        store.markRecorded("005930", "D20", holdSell, Duration.ofMinutes(30));
        store.clearRecorded("005930", "D20", List.of(holdBuy));

        // Assert
        String key = FilterKeys.recorded("005930", "D20");
        assertThat(List.of(first, second)).containsExactly(true, false);
        assertThat(redisTemplate.opsForSet().members(key))
                .containsExactly("HOLD>SELL:DWELL_PENDING");
        assertThat(redisTemplate.getExpire(key)).isBetween(1_700L, 1_800L);
    }

    @Test
    @DisplayName("쿨다운은 Tier별 키로 걸리고 장 시작 리셋이 모든 Tier를 지운다")
    void cooldowns_arePerTierAndResettable() {
        // Arrange
        store.startCooldown("005930", "D20", 1, Grade.STRONG_BUY, Duration.ofMinutes(20));
        store.startCooldown("005930", "D20", 2, Grade.BUY, Duration.ofMinutes(30));

        // Act & Assert
        assertThat(store.cooldown("005930", "D20", 1)).contains(Grade.STRONG_BUY);
        assertThat(redisTemplate.getExpire(FilterKeys.cooldown("005930", "D20", 2)))
                .isBetween(1_700L, 1_800L);
        store.clearCooldowns("005930", "D20");
        assertThat(store.cooldown("005930", "D20", 1)).isEmpty();
        assertThat(store.cooldown("005930", "D20", 2)).isEmpty();
    }

    @Test
    @DisplayName("filter:grade 초기화는 기존 값이 있으면 덮어쓰지 않는다")
    void initGrade_doesNotOverwrite() {
        // Arrange
        store.setGrade("005930", "D20", Grade.HOLD, Duration.ofHours(1));

        // Act
        boolean initialized =
                store.initGradeIfAbsent("005930", "D20", Grade.STRONG_BUY, Duration.ofHours(1));

        // Assert
        assertThat(initialized).isFalse();
        assertThat(store.grade("005930", "D20")).contains(Grade.HOLD);
    }
}
