package com.aaa.notifier.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Actuator 엔드포인트 통합 검증 (REQ-NOTIFIER-FOUNDATION-009/010, AC-3).
 *
 * <p>smoke 프로파일로 Redis AutoConfiguration을 제외하므로 실 Redis 미기동 상태에서도 {@code /actuator/health}가
 * UP(200)을 반환한다. {@code show-details: never}가 지켜지는지는 응답 body에 {@code components}가 없음으로 검증한다
 * (Decision Point 1: HTTP로 components를 노출하지 않고, Redis liveness의 실제 UP/DOWN 판정은 {@code
 * RedisPingHealthIndicatorTest}가 인디케이터를 직접 호출해 검증한다).
 *
 * <p>{@code @Container}가 없어(Testcontainers 미사용) 이 클래스는 단위 계층(`test` 태스크)에서 실행된다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "smoke"})
@TestPropertySource(
        properties = {
            "management.server.port=0",
            // Spring Boot 테스트 컨텍스트는 기본적으로 metrics export를 끈다. 프로덕션에서는 활성이므로
            // /actuator/prometheus 노출을 검증하기 위해 prometheus export만 명시 활성화한다.
            "management.prometheus.metrics.export.enabled=true"
        })
@DisplayName("Actuator 엔드포인트 (health + prometheus)")
class ActuatorEndpointsTest {

    @LocalManagementPort private int managementPort;

    @Autowired private TestRestTemplate restTemplate;

    @Test
    @DisplayName("GET /actuator/health → HTTP 200")
    void health_returnsOk() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/actuator/health"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /actuator/health 응답 body에 status:UP 포함")
    void health_bodyContainsStatusUp() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/actuator/health"), String.class);

        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("show-details: never — 응답 body에 components 없음")
    void health_bodyDoesNotContainComponents() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/actuator/health"), String.class);

        assertThat(response.getBody()).doesNotContain("components");
    }

    @Test
    @DisplayName("GET /actuator/prometheus → HTTP 200 + Prometheus/VM 텍스트 노출 포맷")
    void prometheus_returnsPrometheusTextFormat() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/actuator/prometheus"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Prometheus/OpenMetrics 텍스트 노출 포맷은 # HELP / # TYPE 주석 라인을 포함한다.
        assertThat(response.getBody()).contains("# HELP").contains("# TYPE");
    }

    @Test
    @DisplayName("노출되지 않은 엔드포인트 GET /actuator/beans → HTTP 404 (health,prometheus만 노출)")
    void beans_returnsNotFound() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/actuator/beans"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String url(String path) {
        return "http://localhost:" + managementPort + path;
    }
}
