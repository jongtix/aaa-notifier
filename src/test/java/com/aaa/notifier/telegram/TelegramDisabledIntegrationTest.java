package com.aaa.notifier.telegram;

import static com.aaa.notifier.telegram.TelegramIntegrationSupport.rowCount;
import static com.aaa.notifier.telegram.TelegramIntegrationSupport.withStock;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.notifier.filter.AlertDecisionSink;
import com.aaa.notifier.filter.JdbcDryRunAlertDecisionSink;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 실발송 모드 꺼짐(기본값) — FILTER-001 동작 그대로 (REQ-001, AC-01, AC-16 ⑤).
 *
 * <p>스텁 서버를 띄워 두고 base-url도 그쪽을 가리키게 해, 꺼진 상태에서 요청이 한 건도 나가지 않음을 실측한다.
 */
@SpringBootTest(properties = "notifier.filter.enabled=true")
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("실발송 모드 꺼짐 — FILTER-001 DRYRUN 동작 유지 (실 Redis·MySQL)")
class TelegramDisabledIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4").withInitScript("filter/schema.sql");

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    private static final WireMockServer STUB = new WireMockServer(options().dynamicPort());

    static {
        STUB.start();
    }

    @Autowired private AlertDecisionSink sink;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private StringRedisTemplate redis;
    @Autowired private MeterRegistry meterRegistry;

    private long stockId;

    @DynamicPropertySource
    static void stubUrl(DynamicPropertyRegistry registry) {
        registry.add("notifier.telegram.base-url", STUB::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        STUB.stop();
    }

    @BeforeEach
    void setUp() {
        stockId = TelegramIntegrationSupport.resetState(jdbcTemplate, redis);
    }

    @Test
    @DisplayName(
            "AC-01 — 후보 1건·억제 1건 → DRYRUN 행 2건, 텔레그램 요청 0건, 대기 큐·safe_mode·stream:alert 키 없음, 싱크는 DRYRUN 구현")
    void disabled_keepsFilterDryRun() {
        sink.onDecision(withStock(TelegramAlerts.candidate("trace-c"), stockId));
        sink.onDecision(withStock(TelegramAlerts.suppressed("trace-s"), stockId));

        assertThat(rowCount(jdbcTemplate, "DRYRUN")).isEqualTo(2);
        assertThat(STUB.getAllServeEvents()).isEmpty();
        assertThat(redis.hasKey(RedisPendingQueue.KEY)).isFalse();
        assertThat(redis.hasKey(RedisSafeModeStore.KEY)).isFalse();
        assertThat(redis.hasKey(RedisAlertPublisher.KEY)).isFalse();
        assertThat(sink).isInstanceOf(JdbcDryRunAlertDecisionSink.class);
    }

    @Test
    @DisplayName("AC-16 ⑤ — 실발송 모드가 꺼져 있으면 notifier.telegram.* 계측이 등록되지 않는다")
    void disabled_registersNoTelegramMeters() {
        assertThat(meterRegistry.getMeters())
                .noneMatch(meter -> meter.getId().getName().startsWith("notifier.telegram."));
    }
}
