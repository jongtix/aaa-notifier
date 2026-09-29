package com.aaa.notifier.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aaa.notifier.stream.ConsumedStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 필터 파이프라인 종단 통합 테스트 (plan.md §G M9) — 신호 수신 → 장전 적재 → 틱 스트림 → DRYRUN INSERT 전 경로.
 *
 * <p>소비 계층(실 Redis Streams)부터 결정 싱크(실 MySQL)까지 이 SPEC이 새로 연결한 배선이 실제로 이어지는지 본다 — 핸들러 교체
 * ({@code @ConditionalOnMissingBean}), 신호 → {@code filter:signal} → 결정의 score/confidence, 틱 → 밴드 판정
 * → 게이트 → {@code notification_log}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "notifier.filter.enabled=true",
            // 테스트 컨텍스트는 메트릭 export가 기본 비활성 — /actuator/prometheus 노출 검증(AC-22)을 위해 명시 활성화
            "management.prometheus.metrics.export.enabled=true"
        })
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
@Import(FixedClockConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("필터 파이프라인 종단 통합 테스트 (실 Redis Streams + MySQL)")
class FilterPipelineEndToEndIntegrationTest {

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

    private static final Duration WAIT = Duration.ofSeconds(15);

    private static final String TICK_TRACE_ID = "0b6f7c2e-1a3d-4e5f-9a7b-8c9d0e1f2a3b";
    private static final String SIGNAL_TRACE_ID = "5d4c3b2a-1f0e-4d9c-8b7a-6f5e4d3c2b1a";

    /**
     * 국내 체결 {@code H0STCNT0} 레코드 1건(47필드) — stream 패키지 {@code
     * KisFrameFixtures.DOMESTIC_TRADE_RECORD}(ws-01 실측 원문)에서 {@code [1]} 체결 시각을 10:00:00, {@code
     * [2]} 체결가를 29,000, {@code [13]} 누적 거래량을 100,000으로 바꾼 것이다.
     */
    private static final String DOMESTIC_TRADE_RECORD =
            "005930^100000^29000^5^-40500^-11.46^335674.46^347500^353000^310000^313500^313000"
                    + "^825^100000^11581539743500^382205^358363^-23842^67.05^19740248^13236777"
                    + "^5^0.39^141.81^090008^5^-34500^090010^5^-40000^151413^2^3000^20260623^20^N"
                    + "^2751^7557^92085^151119^0.59^21647427^159.38^0^^322000^2";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private ReferenceDataRefresher refresher;
    @Autowired private TestRestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        FilterDbFixtures.truncate(jdbcTemplate);
        Set<String> filterKeys = redisTemplate.keys("filter:*");
        if (filterKeys != null && !filterKeys.isEmpty()) {
            redisTemplate.delete(filterKeys);
        }
        long stockId = FilterDbFixtures.insertStock(jdbcTemplate, "005930", "KOSPI");
        FilterDbFixtures.insertFlatOhlcv(jdbcTemplate, stockId, 25, "30000", "100", 1_000_000L);
        FilterDbFixtures.insertBands(
                jdbcTemplate,
                stockId,
                "D20",
                "PROMOTE",
                List.of(
                        new String[] {"21000", "30490", "BUY"},
                        new String[] {"30500", "39000", "STRONG_BUY"}));
        FilterDbFixtures.insertBands(
                jdbcTemplate,
                stockId,
                "D20",
                "DEMOTE",
                List.of(
                        new String[] {"21000", "29790", "BUY"},
                        new String[] {"29800", "39000", "STRONG_BUY"}));
    }

    private void publish(ConsumedStream stream, Map<String, String> fields) {
        redisTemplate.opsForStream().add(MapRecord.create(stream.getKey(), fields));
    }

    private Integer dryRunRows(String traceId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_log WHERE trace_id = ? AND event_type = 'DRYRUN'",
                Integer.class,
                traceId);
    }

    @Test
    @DisplayName("신호 → 장전 적재 → 틱 → STRONG_BUY→BUY(Tier 3) 결정이 notification_log DRYRUN 행으로 남는다")
    void signalThenTick_persistsDryRunDecision() {
        // Arrange — 장전 적재(유효 등급 초기값 = DEMOTE(30,000) = STRONG_BUY) + 전일 신호
        refresher.onDomesticPreOpen();
        publish(
                ConsumedStream.SIGNAL_DOMESTIC,
                Map.of(
                        "symbol", "005930",
                        "horizon", "D20",
                        "trade_date", "2026-09-28",
                        "trace_id", SIGNAL_TRACE_ID,
                        "signal_class", "BUY",
                        "score", "0.021",
                        "confidence", "0.600"));
        await().atMost(WAIT).until(() -> redisTemplate.hasKey(FilterKeys.signal("005930", "D20")));

        // Act — 29,000 체결: 양 파티션 BUY 일치 → 강도 완화(무확증 Tier 3), 가드 통과(10:00, 누적 100,000)
        publish(
                ConsumedStream.TICK_DOMESTIC,
                Map.of(
                        "symbol",
                        "005930",
                        "trId",
                        "H0STCNT0",
                        "data",
                        DOMESTIC_TRADE_RECORD,
                        "trace_id",
                        TICK_TRACE_ID));

        // Assert
        await().atMost(WAIT).until(() -> dryRunRows(TICK_TRACE_ID) == 1);
        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        "SELECT * FROM notification_log WHERE trace_id = ?", TICK_TRACE_ID);
        assertThat(row)
                .containsEntry("signal_class", "BUY")
                .containsEntry("horizon", "D20")
                .containsEntry("message_text", null);
        assertThat(row.get("tier").toString()).isEqualTo("3");
        assertThat(row.get("confidence").toString()).isEqualTo("0.600");
        assertThat(redisTemplate.opsForValue().get(FilterKeys.grade("005930", "D20")))
                .isEqualTo("BUY");
    }

    @Test
    @DisplayName("AC-22 — /actuator/prometheus에 첫 틱 이전부터 확증 대기 게이지가 노출된다")
    void prometheusExposesWarmStartGauge() {
        // Act
        String body = restTemplate.getForObject("/actuator/prometheus", String.class);

        // Assert
        assertThat(body)
                .contains("notifier_filter_confirm_pending")
                .contains("notifier_filter_reference_stocks");
    }
}
