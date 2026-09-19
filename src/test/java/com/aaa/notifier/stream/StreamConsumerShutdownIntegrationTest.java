package com.aaa.notifier.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aaa.notifier.AaaNotifierApplication;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 종료 신호 시 정리 통합 테스트 (REQ-NOTIFIER-CONSUMER-003, AC-4 ①②).
 *
 * <p>애플리케이션 컨텍스트를 <b>테스트가 직접 기동·종료</b>한다. Spring 테스트 컨텍스트 캐시가 관리하는 컨텍스트를 테스트 본문에서 닫으면 이후의 테스트 실행
 * 리스너가 "컨텍스트가 활성 상태가 아님"으로 실패하기 때문이다. {@code @SpringBootTest}가 아니므로 컨텍스트가 컨테이너보다 오래 살 수 없다.
 *
 * <p>{@code @Container} 필드를 보유하므로 클래스 레벨 {@code @Tag("integration")}가 필수다.
 */
@Testcontainers
@Tag("integration")
@DisplayName("소비 단위 종료 정리 통합 테스트 (실 Redis)")
class StreamConsumerShutdownIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    private static final Duration WAIT = Duration.ofSeconds(15);

    /** {@code spring.lifecycle.timeout-per-shutdown-phase}의 운영 값(application.yml). */
    private static final Duration SHUTDOWN_PHASE_TIMEOUT = Duration.ofSeconds(30);

    private ConfigurableApplicationContext context;

    @BeforeEach
    void startApplication() {
        context =
                new SpringApplicationBuilder(
                                AaaNotifierApplication.class, RecordingHandlerConfiguration.class)
                        .web(WebApplicationType.NONE)
                        .profiles("test")
                        // 커맨드라인 인자가 application-test.yml의 기본 접속 값보다 우선한다(properties()는 최하위 우선순위다).
                        .run(
                                "--spring.data.redis.host=" + REDIS.getHost(),
                                "--spring.data.redis.port=" + REDIS.getFirstMappedPort());
    }

    @AfterEach
    void closeApplication() {
        if (context != null && context.isActive()) {
            context.close();
        }
    }

    @Test
    @DisplayName("AC-4 — 컨텍스트 종료가 30s 안에 끝나고 종료 뒤 소비 스레드가 하나도 살아 있지 않다")
    void contextClose_completesWithinShutdownBudget_andLeavesNoConsumerThread() {
        // Arrange — 4스트림에서 1건씩 소비시켜, 스트림당 1개인 소비 스레드를 전달 스레드로 관측한다.
        StringRedisTemplate redisTemplate = context.getBean(StringRedisTemplate.class);
        RecordingStreamHandler handler = context.getBean(RecordingStreamHandler.class);
        StreamTestSupport.publish(
                redisTemplate,
                ConsumedStream.TICK_DOMESTIC,
                KisFrameFixtures.tickEntry(
                        "005930",
                        "H0STCNT0",
                        KisFrameFixtures.DOMESTIC_TRADE_RECORD,
                        KisFrameFixtures.TRACE_ID));
        StreamTestSupport.publish(
                redisTemplate,
                ConsumedStream.TICK_OVERSEAS,
                KisFrameFixtures.tickEntry(
                        "DNASAAPL",
                        "HDFSCNT0",
                        KisFrameFixtures.OVERSEAS_TRADE_RECORD,
                        KisFrameFixtures.TRACE_ID));
        StreamTestSupport.publish(
                redisTemplate, ConsumedStream.SIGNAL_DOMESTIC, KisFrameFixtures.signalEntry());
        StreamTestSupport.publish(
                redisTemplate, ConsumedStream.SIGNAL_OVERSEAS, KisFrameFixtures.signalEntry());
        await().atMost(WAIT).until(() -> handler.deliveryThreads().size() == 4);
        List<Thread> consumerThreads = handler.deliveryThreads();
        assertThat(consumerThreads).allSatisfy(thread -> assertThat(thread.isAlive()).isTrue());

        // Act — 컨텍스트를 닫는다(SmartLifecycle.stop이 소비 단위를 정리한다).
        long startedAt = System.nanoTime();
        context.close();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        // Assert — 단언 ①: spring.lifecycle.timeout-per-shutdown-phase(30s) 안에 끝난다.
        assertThat(elapsed).isLessThan(SHUTDOWN_PHASE_TIMEOUT);
        // 단언 ②: 종료가 반환된 시점에 소비 스레드가 하나도 살아 있지 않다.
        assertThat(consumerThreads).as("컨텍스트 종료 뒤 잔존 소비 스레드").noneMatch(Thread::isAlive);
    }
}
