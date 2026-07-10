package com.aaa.notifier.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;

/**
 * ECS JSON 구조화 로그 + KST + Trace ID 실측 검증 (REQ-NOTIFIER-FOUNDATION-015/016/018, AC-5).
 *
 * <p>프로덕션의 {@code logging.structured.format.file: ecs}와 동일한 인코더({@link StructuredLogEncoder},
 * format=ecs)를 전용 로거에 붙여 방출한 로그 라인을 읽어, (1) ECS JSON 파싱 가능, (2) {@code @timestamp}가 유효한 ISO
 * instant, (3) 추적 컨텍스트 안 로그에는 발급한 trace_id가 top-level 필드로 포함되고 밖 로그에는 없음을 검증한다. Spring Boot의
 * MDC→ECS 자동 포함 동작을 문서 추론이 아니라 실측으로 고정한다(plan.md R5).
 *
 * <p>SLF4J가 바인딩된 실제 {@link LoggerContext}를 그대로 사용하므로 {@link org.slf4j.MDC}(=&gt; {@link
 * TraceIdManager})에 쓴 trace_id가 로그 이벤트에 자연스럽게 캡처된다. {@code @SpringBootTest}·파일 IO에 의존하지 않아 테스트
 * 순서·컨텍스트 캐싱과 무관하게 결정적이다.
 *
 * <p><b>KST 이중 보장 검증(REQ-016)의 정확한 관찰 지점</b>: ECS 포맷의 {@code @timestamp}는 ECS 표준상 항상 UTC({@code
 * Z})로 직렬화된다(실측: {@code 2026-07-10T12:38:58.949993Z}). 따라서 KST "이중 보장"은 ECS {@code @timestamp}의
 * 오프셋이 아니라 JVM 기본 타임존({@code TimeZone.getDefault()})으로 관찰한다 — build.gradle.kts test 태스크의 {@code
 * -Duser.timezone=Asia/Seoul} jvmArgs와 프로덕션 {@code main()}의 {@code TimeZone.setDefault()}가 그
 * 메커니즘이며, 이것이 콘솔 패턴({@code %d})·로컬 시각 계산에 KST를 적용한다. acceptance.md AC-5의 "{@code @timestamp}가
 * +09:00" 문면은 ECS 표준(UTC)과 상충하므로 /moai sync에서 정정 대상이다(현실 우선 하우스룰).
 */
@DisplayName("ECS JSON 로깅 + KST + Trace ID (AC-5)")
class EcsLoggingTest {

    private static final String ECS_LOGGER = "com.aaa.notifier.ecs-test";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ByteArrayOutputStream captured;
    private OutputStreamAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logbackLogger;

    @BeforeEach
    void setUp() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        // StructuredLogEncoder(ecs)는 Spring Environment를 요구한다(서비스 이름 등). 플레인 단위 테스트에는
        // Spring 로깅 초기화가 없으므로 직접 주입한다.
        if (context.getObject(Environment.class.getName()) == null) {
            context.putObject(Environment.class.getName(), new StandardEnvironment());
        }

        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setFormat("ecs");
        encoder.setContext(context);
        encoder.start();

        captured = new ByteArrayOutputStream();
        appender = new OutputStreamAppender<>();
        appender.setContext(context);
        appender.setEncoder(encoder);
        appender.setOutputStream(captured);
        appender.start();

        logbackLogger = context.getLogger(ECS_LOGGER);
        logbackLogger.addAppender(appender);
        logbackLogger.setLevel(Level.INFO);
        logbackLogger.setAdditive(false);
    }

    @AfterEach
    void tearDown() {
        TraceIdManager.clear();
        if (logbackLogger != null && appender != null) {
            logbackLogger.detachAppender(appender);
        }
        if (appender != null) {
            appender.stop();
        }
    }

    @Test
    @DisplayName("추적 컨텍스트 안 로그는 ECS JSON + 유효 @timestamp + trace_id 포함")
    void logInTraceContext_isEcsJsonWithTraceId() throws Exception {
        Logger log = LoggerFactory.getLogger(ECS_LOGGER);

        String traceId = TraceIdManager.generate();
        log.info("trace-context message");
        TraceIdManager.clear();

        JsonNode line = firstEmittedLine();

        // (1) ECS JSON 파싱 가능
        assertThat(line).isNotNull();
        // (2) @timestamp가 유효한 ISO instant (ECS 표준상 UTC)
        assertThat(OffsetDateTime.parse(line.get("@timestamp").asText())).isNotNull();
        // (3) 발급한 trace_id가 top-level 필드로 포함 (MDC → ECS 자동 포함 실측 확인)
        assertThat(line.hasNonNull(TraceIdManager.MDC_KEY_TRACE_ID)).isTrue();
        assertThat(line.get(TraceIdManager.MDC_KEY_TRACE_ID).asText()).isEqualTo(traceId);
    }

    @Test
    @DisplayName("추적 컨텍스트 밖 로그는 유효한 ECS JSON이며 trace_id가 없다(파싱 무결)")
    void logOutsideTraceContext_isEcsJsonWithoutTraceId() throws Exception {
        Logger log = LoggerFactory.getLogger(ECS_LOGGER);

        TraceIdManager.clear();
        log.info("no-trace-context message");

        JsonNode line = firstEmittedLine();

        assertThat(line).isNotNull();
        assertThat(OffsetDateTime.parse(line.get("@timestamp").asText())).isNotNull();
        assertThat(line.hasNonNull(TraceIdManager.MDC_KEY_TRACE_ID)).isFalse();
    }

    @Test
    @DisplayName("KST 이중 보장 — 테스트 JVM 기본 타임존이 Asia/Seoul (build.gradle.kts jvmArgs 적용)")
    void kstDefaultTimeZone_isAsiaSeoul() {
        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Seoul");
    }

    /** 캡처된 출력에서 첫 ECS JSON 라인을 파싱해 반환한다. */
    private JsonNode firstEmittedLine() throws IOException {
        String output = captured.toString(StandardCharsets.UTF_8);
        String[] lines = output.split("\\r?\\n");
        for (String raw : lines) {
            if (!raw.isBlank()) {
                return MAPPER.readTree(raw);
            }
        }
        return null;
    }
}
