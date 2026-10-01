package com.aaa.notifier.filter;

import static com.aaa.notifier.filter.FilterTestFixtures.domesticReference;
import static com.aaa.notifier.filter.FilterTestFixtures.domesticTick;
import static com.aaa.notifier.filter.FilterTestFixtures.holderOf;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * 등급 유지 확증의 Redis 상태 보존 통합 테스트 (SPEC-NOTIFIER-FILTER-002 REQ-011) — 재시작(파이프라인·저장소 재생성)을 넘어 전환 체결
 * 시각으로 유지 측정을 잇는다.
 *
 * <p>Spring 컨텍스트 없이 실 Redis 위에 파이프라인을 직접 조립한다 — 검증 대상이 Redis에 남은 후보 상태의 승계라서다.
 */
@Testcontainers
@Tag("integration")
@DisplayName("등급 유지 확증 — Redis 상태 보존 통합 테스트 (실 Redis)")
class FilterPipelineDwellIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    private static final String SYMBOL = "005930";
    private static final String HORIZON = "D20";

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private List<AlertDecision> decisions;
    private long accumulated;

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
        decisions = new ArrayList<>();
        accumulated = 100_000L;
    }

    /** 프로세스 기동을 모사한다 — 저장소·파이프라인(장중 고저·게이지 포함)을 새로 만들고 Redis만 공유한다. */
    private FilterPipeline startProcess() {
        return FilterPipelines.create(
                holderOf(
                        domesticReference(
                                Map.of(HORIZON, FilterTestFixtures.uniformBands(Grade.BUY)))),
                new RedisFilterStateStore(redisTemplate),
                decisions::add,
                new SimpleMeterRegistry(),
                FilterTestFixtures.DOMESTIC_SESSION_CLOCK);
    }

    private void tick(FilterPipeline pipeline, LocalTime time) {
        accumulated += 1_000L;
        pipeline.onTradeTick(domesticTick("30000", time, accumulated));
    }

    @Test
    @DisplayName("AC-10 — 유지 측정 도중 재시작해도 저장된 전환 체결 시각(10:00:00)으로 이어서 재고 10:10:00에 발송 후보가 나온다")
    void restartDuringDwell_continuesFromStoredTransitionTime() {
        // Arrange — 기준 HOLD, 10:00:00 BUY 교차(HOLD→BUY, 유지 10분)
        new RedisFilterStateStore(redisTemplate)
                .setGrade(SYMBOL, HORIZON, Grade.HOLD, Duration.ofHours(1));
        tick(startProcess(), LocalTime.of(10, 0));

        // Act — 재시작 후 10:03:00부터 10초 간격 BUY 체결
        FilterPipeline restarted = startProcess();
        LocalTime firstCandidate = null;
        for (LocalTime time = LocalTime.of(10, 3);
                firstCandidate == null && !time.isAfter(LocalTime.of(10, 13));
                time = time.plusSeconds(10)) {
            tick(restarted, time);
            if (decisions.getLast().isCandidate()) {
                firstCandidate = time;
            }
        }

        // Assert — 재시작 기준이면 10:13:00까지 기다렸을 것이다
        assertThat(firstCandidate).isEqualTo(LocalTime.of(10, 10));
        assertThat(redisTemplate.opsForHash().entries(FilterKeys.confirm(SYMBOL, HORIZON)))
                .as("종결된 후보는 지워진다")
                .isEmpty();
    }

    @Test
    @DisplayName("AC-10 — 재시작 직후 저장된 후보의 전환 체결 시각은 교차 체결 시각 그대로다")
    void restart_preservesStoredTransitionTime() {
        // Arrange
        new RedisFilterStateStore(redisTemplate)
                .setGrade(SYMBOL, HORIZON, Grade.HOLD, Duration.ofHours(1));
        tick(startProcess(), LocalTime.of(10, 0));

        // Act — 재시작 후 유지 시간 미충족 체결
        tick(startProcess(), LocalTime.of(10, 3));

        // Assert
        assertThat(
                        new RedisFilterStateStore(redisTemplate)
                                .pending(SYMBOL, HORIZON)
                                .orElseThrow()
                                .since())
                .isEqualTo(
                        ZonedDateTime.of(2026, 9, 29, 10, 0, 0, 0, MarketSession.KST).toInstant());
    }
}
