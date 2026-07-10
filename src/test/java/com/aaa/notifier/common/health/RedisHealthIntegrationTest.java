package com.aaa.notifier.common.health;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * RedisPingHealthIndicator 통합 테스트 (REQ-NOTIFIER-FOUNDATION-012, AC-3/AC-10).
 *
 * <p>실 Redis Testcontainer로 {@link RedisHealthConfig}가 {@link RedisPingHealthIndicator}를 등록하고 PING
 * 기반 liveness가 UP을 반환함을 검증한다. {@code @Container} 필드를 보유하므로 클래스 레벨 {@code @Tag("integration")}가
 * 필수이며(REQ-031, IntegrationTagGuardTest가 강제), 이 클래스는 {@code integrationTest} 태스크(CI check)에서만
 * 실행된다(pre-push 제외).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
@DisplayName("RedisPingHealthIndicator 통합 테스트 (실 Redis)")
class RedisHealthIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    @Autowired private RedisPingHealthIndicator indicator;

    @Test
    @DisplayName("실 Redis 연결 시 헬스 인디케이터가 UP을 반환한다")
    void indicator_returnsUp_withRealRedis() {
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }
}
