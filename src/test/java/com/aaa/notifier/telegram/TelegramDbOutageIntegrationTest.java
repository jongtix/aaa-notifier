package com.aaa.notifier.telegram;

import static com.aaa.notifier.telegram.TelegramIntegrationSupport.SEND_PATH;
import static com.aaa.notifier.telegram.TelegramIntegrationSupport.ok;
import static com.aaa.notifier.telegram.TelegramIntegrationSupport.respondToSend;
import static com.aaa.notifier.telegram.TelegramIntegrationSupport.withStock;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aaa.notifier.filter.AlertDecisionSink;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
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

/** DB 중단 중 성공 발송 — 기록 fail-open (REQ-002 후단, AC-02 ③). MySQL 컨테이너를 멈추므로 이 클래스는 테스트 1건만 둔다. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("MySQL 중단 중 실발송 — 발송 1회·재발송 없음·기록 실패 계측")
class TelegramDbOutageIntegrationTest {

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

    @DynamicPropertySource
    static void telegram(DynamicPropertyRegistry registry) {
        TelegramIntegrationSupport.register(registry, STUB);
    }

    @AfterAll
    static void stopStub() {
        STUB.stop();
    }

    @Test
    @DisplayName("AC-02 ③ — MySQL을 멈춘 뒤 후보 1건 → sendMessage 정확히 1회, 기록 실패 계측 +1")
    void databaseDown_sendsOnceAndCountsRecordFailure() {
        long stockId = TelegramIntegrationSupport.resetState(jdbcTemplate, redis);
        respondToSend(STUB, ok(1));
        MYSQL.stop();

        sink.onDecision(withStock(TelegramAlerts.candidate("trace-db-down"), stockId));

        await().atMost(Duration.ofSeconds(30))
                .until(
                        () ->
                                meterRegistry.counter("notifier.telegram.record.failure").count()
                                        >= 1.0);
        assertThat(STUB.findAll(postRequestedFor(urlPathEqualTo(SEND_PATH)))).hasSize(1);
        assertThat(meterRegistry.counter("notifier.telegram.record.failure").count())
                .isEqualTo(1.0);
    }
}
