package com.aaa.notifier.telegram;

import static com.aaa.notifier.telegram.TelegramIntegrationSupport.SECRET;
import static com.aaa.notifier.telegram.TelegramIntegrationSupport.SEND_PATH;
import static com.aaa.notifier.telegram.TelegramIntegrationSupport.ok;
import static com.aaa.notifier.telegram.TelegramIntegrationSupport.respondToGetMe;
import static com.aaa.notifier.telegram.TelegramIntegrationSupport.respondToSend;
import static com.aaa.notifier.telegram.TelegramIntegrationSupport.rowCount;
import static com.aaa.notifier.telegram.TelegramIntegrationSupport.withStock;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aaa.notifier.filter.AlertDecisionSink;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 실발송 모드 종단 간 통합 테스트 — 실제 Spring 컨텍스트 + 실 Redis·MySQL + 텔레그램 스텁 서버 (AC-02·04·07·11·13·14·15·16).
 *
 * <p>텔레그램은 WireMock 스텁으로만 대체한다 — 실제 {@code api.telegram.org} 호출은 없다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            "management.server.port=0",
            "management.prometheus.metrics.export.enabled=true"
        })
@Testcontainers
@Tag("integration")
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("실발송 모드 종단 간 (실 Redis·MySQL + 텔레그램 스텁)")
class TelegramDeliveryIntegrationTest {

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
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private StringRedisTemplate redis;
    @Autowired private SafeModeProbe probe;
    @Autowired private RedisPendingQueue queue;
    @Autowired private TestRestTemplate restTemplate;
    @LocalManagementPort private int managementPort;

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
        respondToSend(STUB, ok(777));
        stockId = TelegramIntegrationSupport.resetState(jdbcTemplate, redis);
    }

    private List<LoggedRequest> sendRequests() {
        return STUB.findAll(postRequestedFor(urlPathEqualTo(SEND_PATH)));
    }

    @Test
    @DisplayName(
            "AC-02 — 후보 1건·억제 1건 → sendMessage 1건, notification_log는 SENT 1행뿐 (억제·DRYRUN 행 없음)")
    void candidateAndSuppressed_onlyCandidateIsSent() {
        sink.onDecision(withStock(TelegramAlerts.candidate("trace-c"), stockId));
        sink.onDecision(withStock(TelegramAlerts.suppressed("trace-s"), stockId));

        await().atMost(WAIT).until(() -> rowCount(jdbcTemplate, "SENT") == 1);
        assertThat(sendRequests()).hasSize(1);
        Integer total =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification_log", Integer.class);
        assertThat(total).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "AC-07 / AC-14 — SENT 행(message_id 777·실제 본문)과 같은 trace_id·message_id의 stream:alert 엔트리 1건")
    void sent_rowAndStreamEntry() {
        sink.onDecision(withStock(TelegramAlerts.candidate("trace-sent"), stockId));

        await().atMost(WAIT).until(() -> rowCount(jdbcTemplate, "SENT") == 1);
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM notification_log");
        assertThat(row)
                .containsEntry("trace_id", "trace-sent")
                .containsEntry("telegram_message_id", 777L)
                .containsEntry("notification_type", "TIMING");
        assertThat(row.get("message_text").toString()).contains("005930").contains("<b>BUY</b>");
        List<MapRecord<String, Object, Object>> entries =
                redis.opsForStream().range(RedisAlertPublisher.KEY, Range.unbounded());
        assertThat(entries)
                .singleElement()
                .satisfies(
                        e ->
                                assertThat(e.getValue())
                                        .containsEntry("trace_id", "trace-sent")
                                        .containsEntry("telegram_message_id", "777"));
    }

    @Test
    @DisplayName("AC-04 — 스텁이 느려도 결정 수신은 즉시 반환하고, 요청은 투입 순서대로 최소 간격 이상 벌어진다")
    void slowTelegram_doesNotBlockDecisionIntake() {
        respondToSend(STUB, ok(1).withFixedDelay(300));

        for (int i = 0; i < 3; i++) {
            long started = System.nanoTime();
            sink.onDecision(withStock(TelegramAlerts.candidate("order-" + i), stockId));
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofMillis(100));
        }

        await().atMost(WAIT).until(() -> rowCount(jdbcTemplate, "SENT") == 3);
        List<LoggedRequest> requests = sendRequests();
        assertThat(requests).extracting(r -> r.getBodyAsString().contains("order-")).hasSize(3);
        for (int i = 1; i < requests.size(); i++) {
            long gap =
                    requests.get(i).getLoggedDate().getTime()
                            - requests.get(i - 1).getLoggedDate().getTime();
            assertThat(gap).isGreaterThanOrEqualTo(200L);
        }
        List<String> traceOrder =
                jdbcTemplate.queryForList(
                        "SELECT trace_id FROM notification_log ORDER BY id", String.class);
        assertThat(traceOrder).containsExactly("order-0", "order-1", "order-2");
    }

    @Test
    @DisplayName("AC-11 ② — 운영자 ON(재기동 없이) → sendMessage 없이 DRYRUN 행 1건, 대기 큐·stream:alert 없음")
    void operatorOn_dryRunWithoutRestart() {
        redis.opsForValue().set(RedisSafeModeStore.KEY, "ON");

        sink.onDecision(withStock(TelegramAlerts.candidate("trace-on"), stockId));

        await().atMost(WAIT).until(() -> rowCount(jdbcTemplate, "DRYRUN") == 1);
        assertThat(sendRequests()).isEmpty();
        assertThat(redis.hasKey(RedisPendingQueue.KEY)).isFalse();
        assertThat(redis.hasKey(RedisAlertPublisher.KEY)).isFalse();
    }

    @Test
    @DisplayName("AC-09 / AC-11 — 503이 계속되면 3건 소진 뒤 safe_mode가 AUTO가 되고 각 알림은 대기 큐 + QUEUED")
    void persistentFailure_entersAuto() {
        respondToSend(STUB, aResponse().withStatus(503));

        for (int i = 0; i < 3; i++) {
            sink.onDecision(withStock(TelegramAlerts.candidate("fail-" + i), stockId));
        }

        await().atMost(WAIT).until(() -> rowCount(jdbcTemplate, "QUEUED") == 3);
        assertThat(sendRequests()).hasSize(9);
        assertThat(redis.opsForValue().get(RedisSafeModeStore.KEY)).isEqualTo("AUTO");
        assertThat(queue.size()).isEqualTo(3);
    }

    @Test
    @DisplayName(
            "AC-12 / AC-13 — AUTO에서 getMe 성공 프로브 → OFF 해제 + 대기 큐 요약 1건(SUMMARY_SENT·QUEUE_SUMMARY), 큐 비움")
    void probeReleases_thenSummarySent() {
        LocalDateTime now = LocalDateTime.now();
        queue.append(
                QueuedAlert.of(
                        TelegramAlerts.candidate("q-1"),
                        now.minusMinutes(5),
                        DivertReason.SAFE_MODE));
        queue.append(
                QueuedAlert.of(
                        TelegramAlerts.candidate("q-2"),
                        now.minusMinutes(4),
                        DivertReason.SAFE_MODE));
        redis.opsForValue().set(RedisSafeModeStore.KEY, "AUTO");
        respondToGetMe(STUB, 200);

        probe.probe();

        assertThat(redis.opsForValue().get(RedisSafeModeStore.KEY)).isEqualTo("OFF");
        await().atMost(WAIT).until(() -> rowCount(jdbcTemplate, "SUMMARY_SENT") == 1);
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM notification_log");
        assertThat(row)
                .containsEntry("notification_type", "QUEUE_SUMMARY")
                .containsEntry("stock_id", null);
        assertThat(row.get("message_text").toString()).contains("미발송 2건");
        assertThat(queue.size()).isZero();
    }

    @Test
    @DisplayName(
            "AC-16 — /actuator/prometheus에 대기 큐·출처별 safe_mode 게이지와 발송·지연·실패·폐기 시리즈가 있다 (게이지 _total 없음)")
    void prometheus_exposesTelegramSeries() {
        String body =
                restTemplate.getForObject(
                        "http://localhost:" + managementPort + "/actuator/prometheus",
                        String.class);

        assertThat(body)
                .contains("notifier_telegram_queue_depth ")
                .contains("notifier_telegram_safe_mode{source=\"auto\"}")
                .contains("notifier_telegram_safe_mode{source=\"operator\"}")
                .contains("notifier_telegram_send_total{channel=\"alert\",result=\"success\"}")
                .contains("notifier_telegram_send_latency_seconds_count")
                .contains("notifier_telegram_record_failure_total")
                .contains("notifier_telegram_alert_publish_failure_total")
                .contains("notifier_telegram_queue_discarded_total{reason=\"retention_expired\"}")
                .contains(
                        "notifier_telegram_queue_discarded_total{reason=\"summary_permanent_failure\"}")
                .doesNotContain("notifier_telegram_queue_depth_total")
                .doesNotContain("notifier_telegram_safe_mode_total");
    }

    @Test
    @DisplayName("AC-15 — 성공·429·503·400·getMe 실패를 실컨텍스트로 돌려도 로그·행·stream:alert에 토큰 원문이 없다")
    void noTokenLeak_endToEnd(CapturedOutput output) {
        respondToSend(
                STUB,
                aResponse()
                        .withStatus(400)
                        .withBody("{\"ok\":false,\"description\":\"Bad Request\"}"));
        sink.onDecision(withStock(TelegramAlerts.candidate("leak-400"), stockId));
        await().atMost(WAIT).until(() -> rowCount(jdbcTemplate, "SEND_FAILED") == 1);
        respondToSend(STUB, ok(5));
        sink.onDecision(withStock(TelegramAlerts.candidate("leak-ok"), stockId));
        await().atMost(WAIT).until(() -> rowCount(jdbcTemplate, "SENT") == 1);
        redis.opsForValue().set(RedisSafeModeStore.KEY, "AUTO");
        respondToGetMe(STUB, 500);
        probe.probe();

        assertThat(output.getAll()).doesNotContain(SECRET);
        assertThat(
                        jdbcTemplate.queryForList(
                                "SELECT message_text FROM notification_log", String.class))
                .allSatisfy(text -> assertThat(text).doesNotContain(SECRET));
        assertThat(redis.opsForStream().range(RedisAlertPublisher.KEY, Range.unbounded()))
                .allSatisfy(e -> assertThat(e.getValue().toString()).doesNotContain(SECRET));
    }
}
