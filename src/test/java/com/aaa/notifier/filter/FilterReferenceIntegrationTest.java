package com.aaa.notifier.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.notifier.stream.Market;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 장전 적재 통합 테스트 (REQ-001/003, AC-1 ③, AC-2) — 실 MySQL + 실 Redis.
 *
 * <p>SQL 3종이 운영 스키마 형태의 테이블에서 실제로 동작하는지(행 매퍼·IN 서브쿼리·날짜 경계)와, 유효 등급 초기화가 Redis에 실제로 반영·승계되는지를 확인한다.
 * 쿼리 횟수 단언(AC-1 ①)은 {@link ReferenceDataLoaderTest}가 맡는다.
 */
@SpringBootTest(properties = "notifier.filter.enabled=true")
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
@Import(FixedClockConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("장전 적재 통합 테스트 (실 MySQL + Redis)")
class FilterReferenceIntegrationTest {

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

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private ReferenceDataRefresher refresher;
    @Autowired private ReferenceDataHolder holder;

    private long stockId;

    @BeforeEach
    void setUp() {
        FilterDbFixtures.truncate(jdbcTemplate);
        redisTemplate.delete(FilterStateStore.gradeKey("005930", "D20"));
        stockId = FilterDbFixtures.insertStock(jdbcTemplate, "005930", "KOSPI");
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

    @Test
    @DisplayName("AC-1 — 장전 cron 1회로 참조 데이터·밴드가 프로세스 메모리에 적재된다")
    void domesticPreOpen_loadsReferenceIntoMemory() {
        // Act
        refresher.onDomesticPreOpen();

        // Assert
        StockReference reference = holder.current().find(Market.DOMESTIC, "005930").orElseThrow();
        assertThat(reference.stockId()).isEqualTo(stockId);
        assertThat(reference.prevClose()).isEqualByComparingTo("30000");
        assertThat(reference.averageVolume()).isEqualByComparingTo("1000000");
        assertThat(reference.atr()).isEqualByComparingTo("200");
        assertThat(reference.bands()).containsOnlyKeys("D20");
    }

    @Test
    @DisplayName("AC-1 ③ — 미존재 유효 등급이 전일 종가 DEMOTE 파티션 등급으로 초기화된다")
    void domesticPreOpen_initializesMissingGradeFromDemote() {
        // Act
        refresher.onDomesticPreOpen();

        // Assert — 전일 종가 30,000: PROMOTE=BUY, DEMOTE=STRONG_BUY → DEMOTE 기준
        assertThat(redisTemplate.opsForValue().get(FilterStateStore.gradeKey("005930", "D20")))
                .isEqualTo("STRONG_BUY");
        assertThat(redisTemplate.getExpire(FilterStateStore.gradeKey("005930", "D20")))
                .as("장 마감(15:30)까지 TTL — 10:00 기준 5.5시간")
                .isBetween(19_000L, 19_800L);
    }

    @Test
    @DisplayName("AC-2 — 장중 재시작 시 기존 유효 등급을 덮어쓰지 않고 승계한다")
    void restart_inheritsExistingGrade() {
        // Arrange
        redisTemplate.opsForValue().set(FilterStateStore.gradeKey("005930", "D20"), "HOLD");

        // Act
        refresher.onApplicationReady();

        // Assert
        assertThat(redisTemplate.opsForValue().get(FilterStateStore.gradeKey("005930", "D20")))
                .isEqualTo("HOLD");
        assertThat(holder.current().find(Market.DOMESTIC, "005930")).isPresent();
    }
}
