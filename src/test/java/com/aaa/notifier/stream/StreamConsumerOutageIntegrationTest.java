package com.aaa.notifier.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Redis 연결 두절 중 소비 단위 생존 통합 테스트 (REQ-NOTIFIER-CONSUMER-014, AC-8 ①②③).
 *
 * <p>소비 계층의 Redis 접속을 {@link TcpProxy}로 돌려 놓고 <b>연결을 실제로 끊었다</b> 되살린다(신규 접속 거부 + 기존 연결 리셋 → 같은 포트
 * 복구). 테스트 쪽 발행은 프록시를 거치지 않는 별도 연결로 하므로 두절이 발행 자체를 흔들지 않는다.
 *
 * <p>{@code @Container} 필드를 보유하므로 클래스 레벨 {@code @Tag("integration")}가 필수다.
 */
@SpringBootTest(
        properties = {
            "notifier.stream.error-backoff=200ms",
            "notifier.stream.block-timeout=500ms",
            // 운영 기본값(application.yml, 5s — RedisCommandTimeoutDefaultsTest가 검증)보다 더 짧게 줘서
            // 오류 기록·백오프 동작을 테스트 대기 시간 안에 관측한다.
            "spring.data.redis.timeout=1s",
            "spring.data.redis.connect-timeout=1s"
        })
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
@Import(RecordingHandlerConfiguration.class)
// 컨텍스트가 컨테이너보다 오래 살지 않게 한다 — ContainerContextLifecycleGuardTest 참조.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Redis 연결 두절 통합 테스트 (실 Redis + 끊을 수 있는 중계기)")
class StreamConsumerOutageIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    /** 소비 계층 ↔ Redis 사이의 끊을 수 있는 경로. 업스트림은 컨테이너가 뜬 뒤에 조회된다. */
    private static final TcpProxy PROXY =
            TcpProxy.listening(
                    () -> new InetSocketAddress(REDIS.getHost(), REDIS.getFirstMappedPort()));

    private static final Duration ERROR_BACKOFF = Duration.ofMillis(200);
    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired private RecordingStreamHandler handler;
    @Autowired private StreamConsumerRunner runner;
    @Autowired private ConfigurableApplicationContext context;

    @DynamicPropertySource
    static void redisThroughProxy(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", PROXY::port);
    }

    @AfterAll
    static void closeProxy() {
        PROXY.close();
    }

    @Test
    @DisplayName("AC-8 — 연결이 끊긴 동안에도 소비 단위가 살아 있고 오류를 기록하며, 복구 뒤 소비를 자동 재개한다")
    void redisOutage_keepsConsumersAlive_logsErrors_andResumesAfterRecovery() {
        LettuceConnectionFactory directFactory =
                new LettuceConnectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort());
        directFactory.afterPropertiesSet();
        StringRedisTemplate directRedis = new StringRedisTemplate(directFactory);
        ErrorLogCollector errors = ErrorLogCollector.attach();
        try {
            // Arrange — 두절 전에 4스트림에서 1건씩 소비시켜 소비 스레드를 관측한다.
            publishAllFourStreams(directRedis);
            await().atMost(WAIT)
                    .until(
                            () ->
                                    handler.deliveryThreads().size() == 4
                                            && handler.trades().size() >= 2
                                            && handler.signals().size() >= 2);
            List<Thread> consumerThreads = handler.deliveryThreads();
            handler.clear();
            errors.clear();

            // Act — 연결을 끊고, 오류 백오프 주기의 3배가 지나도록(그리고 모든 소비 단위가 오류를 기록하도록) 둔다.
            Instant severedAt = Instant.now();
            PROXY.sever();
            await().atMost(WAIT)
                    .until(
                            () ->
                                    Duration.between(severedAt, Instant.now())
                                                            .compareTo(
                                                                    ERROR_BACKOFF.multipliedBy(3))
                                                    >= 0
                                            && errorsPerStream(errors).stream()
                                                    .allMatch(count -> count >= 1));

            // Assert ① — 두절 구간 동안 컨텍스트가 종료되지 않았고 소비 스레드도 그대로 살아 있다.
            assertThat(context.isActive()).isTrue();
            assertThat(runner.isRunning()).isTrue();
            assertThat(consumerThreads).allSatisfy(thread -> assertThat(thread.isAlive()).isTrue());
            // Assert ③ — 침묵 실패가 아니다: 4개 소비 단위 전부가 두절 구간에 오류를 남겼다.
            assertThat(errorsPerStream(errors))
                    .hasSize(4)
                    .allSatisfy(count -> assertThat(count).isPositive());

            // Act — 연결을 되살리고 새 메시지를 발행한다.
            PROXY.restore();
            publishAllFourStreams(directRedis);

            // Assert ② — 복구 뒤 4스트림 전부에서 새 메시지가 하류에 전달된다(같은 소비 스레드가 재개한다).
            await().atMost(WAIT)
                    .until(() -> handler.trades().size() >= 2 && handler.signals().size() >= 2);
            assertThat(handler.deliveryThreads())
                    .allSatisfy(thread -> assertThat(consumerThreads).contains(thread));
        } finally {
            errors.detach();
            directFactory.destroy();
        }
    }

    private static void publishAllFourStreams(StringRedisTemplate redis) {
        StreamTestSupport.publish(
                redis,
                ConsumedStream.TICK_DOMESTIC,
                KisFrameFixtures.tickEntry(
                        "005930",
                        "H0STCNT0",
                        KisFrameFixtures.DOMESTIC_TRADE_RECORD,
                        KisFrameFixtures.TRACE_ID));
        StreamTestSupport.publish(
                redis,
                ConsumedStream.TICK_OVERSEAS,
                KisFrameFixtures.tickEntry(
                        "DNASAAPL",
                        "HDFSCNT0",
                        KisFrameFixtures.OVERSEAS_TRADE_RECORD,
                        KisFrameFixtures.TRACE_ID));
        StreamTestSupport.publish(
                redis, ConsumedStream.SIGNAL_DOMESTIC, KisFrameFixtures.signalEntry());
        StreamTestSupport.publish(
                redis, ConsumedStream.SIGNAL_OVERSEAS, KisFrameFixtures.signalEntry());
    }

    /** 스트림별 ERROR 로그 건수 (4스트림 순서 고정). */
    private static List<Long> errorsPerStream(ErrorLogCollector errors) {
        return Arrays.stream(ConsumedStream.values())
                .map(stream -> errors.countFor(stream.getKey()))
                .toList();
    }

    /** 소비 워커의 ERROR 로그를 스레드 안전하게 모으는 Logback 어펜더 (4개 워커가 동시에 기록한다). */
    private static final class ErrorLogCollector extends AppenderBase<ILoggingEvent> {

        private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
        private final Logger logger = (Logger) LoggerFactory.getLogger(StreamConsumerWorker.class);

        static ErrorLogCollector attach() {
            ErrorLogCollector collector = new ErrorLogCollector();
            collector.start();
            collector.logger.addAppender(collector);
            return collector;
        }

        @Override
        protected void append(ILoggingEvent event) {
            if (event.getLevel() == Level.ERROR) {
                events.add(event);
            }
        }

        long countFor(String streamKey) {
            return events.stream()
                    .filter(event -> event.getFormattedMessage().contains("stream=" + streamKey))
                    .count();
        }

        void clear() {
            events.clear();
        }

        void detach() {
            logger.detachAppender(this);
            stop();
        }
    }
}
