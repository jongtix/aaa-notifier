package com.aaa.notifier.telegram;

import static com.aaa.notifier.telegram.TelegramIntegrationSupport.SEND_PATH;
import static com.aaa.notifier.telegram.TelegramIntegrationSupport.ok;
import static com.aaa.notifier.telegram.TelegramIntegrationSupport.respondToSend;
import static com.aaa.notifier.telegram.TelegramIntegrationSupport.rowCount;
import static com.aaa.notifier.telegram.TelegramIntegrationSupport.withStock;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aaa.notifier.filter.AlertDecisionSink;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
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

/** 정상 종료 시 미발송 후보 이관 (REQ-004·031, AC-04 ③ ④) — 테스트마다 디스패처를 멈추므로 컨텍스트를 테스트마다 새로 띄운다. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("실발송 정상 종료 — 버퍼 잔량 이관 (실 Redis·MySQL + 무응답 스텁)")
class TelegramShutdownIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

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
    @Autowired private TelegramDispatcher dispatcher;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private StringRedisTemplate redis;
    @Autowired private RedisPendingQueue queue;

    private long stockId;

    @DynamicPropertySource
    static void telegram(DynamicPropertyRegistry registry) {
        TelegramIntegrationSupport.register(registry, STUB);
    }

    @AfterAll
    static void stopStub() {
        STUB.stop();
    }

    @BeforeEach
    void setUp() {
        STUB.resetAll();
        // 응답 타임아웃(1초)보다 길게 멈추는 스텁 — 사실상 무응답
        respondToSend(STUB, ok(1).withFixedDelay(5_000));
        stockId = TelegramIntegrationSupport.resetState(jdbcTemplate, redis);
    }

    private void submitTwoAndWaitForFirstRequest() {
        sink.onDecision(withStock(TelegramAlerts.candidate("pending-1"), stockId));
        sink.onDecision(withStock(TelegramAlerts.candidate("pending-2"), stockId));
        await().atMost(WAIT)
                .until(() -> !STUB.findAll(postRequestedFor(urlPathEqualTo(SEND_PATH))).isEmpty());
    }

    @Test
    @DisplayName("AC-04 ③ — 스텁이 무응답인 채 정상 종료하면 미발송 후보가 대기 큐로 가고 각각 QUEUED 행이 남는다")
    void shutdown_movesUnsentToQueue() {
        submitTwoAndWaitForFirstRequest();

        dispatcher.stop();

        assertThat(queue.size()).isEqualTo(2);
        assertThat(rowCount(jdbcTemplate, "QUEUED")).isEqualTo(2);
        assertThat(rowCount(jdbcTemplate, "SENT")).isZero();
    }

    @Test
    @DisplayName("AC-04 ④ — 종료 전에 운영자 ON이 켜지면 미발송 후보는 대기 큐 대신 DRYRUN, QUEUED 0건")
    void shutdownUnderOperatorOn_dryRun() {
        submitTwoAndWaitForFirstRequest();
        redis.opsForValue().set(RedisSafeModeStore.KEY, "ON");

        dispatcher.stop();

        assertThat(queue.size()).isZero();
        assertThat(rowCount(jdbcTemplate, "DRYRUN")).isEqualTo(2);
        assertThat(rowCount(jdbcTemplate, "QUEUED")).isZero();
    }
}
