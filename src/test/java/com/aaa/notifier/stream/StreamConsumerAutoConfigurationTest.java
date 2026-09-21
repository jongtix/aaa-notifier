package com.aaa.notifier.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 소비 계층 배선 테스트 (REQ-NOTIFIER-CONSUMER-012/051, AC-6 ①, AC-16 ①, 엣지케이스 E6).
 *
 * <p>컨테이너 없이 {@link ApplicationContextRunner}로 조건부 배선만 확인한다 — 설정 키 바인딩과 빈 backoff는 실 Redis가 필요 없는
 * 관심사이고, 컨테이너를 붙이면 pre-push에서 제외해야 해 회귀 보호가 늦어진다.
 */
@DisplayName("StreamConsumerAutoConfiguration — 조건부 배선 (REQ-012/051, AC-6/AC-16/E6)")
class StreamConsumerAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(StreamConsumerAutoConfiguration.class))
                    .withBean(
                            StringRedisTemplate.class,
                            StreamConsumerAutoConfigurationTest::stubbedRedisTemplate);

    /**
     * {@code opsForStream()}까지 스텁한 템플릿을 준다.
     *
     * <p>맨 mock을 주면 {@code opsForStream()}이 null을 돌려주고, 컨텍스트 refresh 때 {@code SmartLifecycle}이 러너를
     * 기동하면서 그룹 생성 단계에서 NPE로 컨텍스트가 통째로 죽는다 — 배선을 보려던 테스트가 배선을 못 보게 된다.
     */
    private static StringRedisTemplate stubbedRedisTemplate() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        StreamOperations<String, String, String> operations = mock(StreamOperations.class);
        when(template.<String, String>opsForStream()).thenReturn(operations);
        when(operations.pending(anyString(), anyString(), any(), anyLong()))
                .thenReturn(new PendingMessages(ConsumedStream.CONSUMER_GROUP, List.of()));
        when(operations.read(
                        any(Consumer.class),
                        any(StreamReadOptions.class),
                        any(StreamOffset[].class)))
                .thenReturn(List.of());
        return template;
    }

    @Nested
    @DisplayName("AC-6 ① — 설정 6키 외부화")
    class ExternalizedProperties {

        @Test
        @DisplayName("6개 키가 모두 `notifier.stream` 접두사로 바인딩된다")
        void allSixKeysBind() {
            runner.withPropertyValues(
                            "notifier.stream.enabled=true",
                            "notifier.stream.block-timeout=3s",
                            "notifier.stream.read-batch-size=25",
                            "notifier.stream.claim-idle-threshold=90s",
                            "notifier.stream.max-delivery-count=5",
                            "notifier.stream.error-backoff=7s")
                    .run(
                            context -> {
                                StreamConsumerProperties properties =
                                        context.getBean(StreamConsumerProperties.class);

                                assertThat(properties.enabled()).isTrue();
                                assertThat(properties.blockTimeout())
                                        .isEqualTo(Duration.ofSeconds(3));
                                assertThat(properties.readBatchSize()).isEqualTo(25);
                                assertThat(properties.claimIdleThreshold())
                                        .isEqualTo(Duration.ofSeconds(90));
                                assertThat(properties.maxDeliveryCount()).isEqualTo(5);
                            });
        }

        @Test
        @DisplayName("error-backoff도 외부 키로 바인딩된다")
        void errorBackoffBinds() {
            runner.withPropertyValues("notifier.stream.error-backoff=7s")
                    .run(
                            context ->
                                    assertThat(
                                                    context.getBean(StreamConsumerProperties.class)
                                                            .errorBackoff())
                                            .isEqualTo(Duration.ofSeconds(7)));
        }

        @Test
        @DisplayName("dlq-max-len도 외부 키로 바인딩된다 (TECHSPEC §5.1 MAXLEN 외부화)")
        void dlqMaxLenBinds() {
            runner.withPropertyValues("notifier.stream.dlq-max-len=42")
                    .run(
                            context ->
                                    assertThat(
                                                    context.getBean(StreamConsumerProperties.class)
                                                            .dlqMaxLen())
                                            .isEqualTo(42L));
        }

        @Test
        @DisplayName("키를 주지 않으면 기본값이 적용된다 (소스 하드코딩이 아니라 선언된 기본값)")
        void defaultsApplyWhenAbsent() {
            runner.run(
                    context -> {
                        StreamConsumerProperties properties =
                                context.getBean(StreamConsumerProperties.class);

                        assertThat(properties.enabled()).isTrue();
                        assertThat(properties.maxDeliveryCount()).isEqualTo(3);
                        assertThat(properties.blockTimeout()).isEqualTo(Duration.ofSeconds(2));
                        assertThat(properties.claimIdleThreshold())
                                .isEqualTo(Duration.ofSeconds(30));
                        assertThat(properties.dlqMaxLen()).isEqualTo(500L);
                    });
        }
    }

    @Nested
    @DisplayName("AC-16 ① — 무동작 기본 핸들러")
    class DefaultHandler {

        @Test
        @DisplayName("FILTER-001 구현체가 없으면 무동작 기본 핸들러가 주입된다")
        void noOpHandlerIsRegisteredByDefault() {
            runner.run(
                    context ->
                            assertThat(context.getBean(StreamObservationHandler.class))
                                    .isInstanceOf(
                                            StreamConsumerAutoConfiguration
                                                    .NoOpStreamObservationHandler.class));
        }

        @Test
        @DisplayName("사용자 구현체가 있으면 기본 구현이 물러난다 (FILTER-001 진입점)")
        void customHandlerWins() {
            runner.withUserConfiguration(CustomHandlerConfiguration.class)
                    .run(
                            context -> {
                                assertThat(context).hasSingleBean(StreamObservationHandler.class);
                                assertThat(context.getBean(StreamObservationHandler.class))
                                        .isInstanceOf(CustomHandler.class);
                            });
        }

        @Test
        @DisplayName("무동작 핸들러는 어떤 관측치에도 예외를 던지지 않는다")
        void noOpHandlerSwallowsEverything() {
            StreamObservationHandler handler =
                    new StreamConsumerAutoConfiguration.NoOpStreamObservationHandler();

            handler.onTradeTick(
                    new TradeTickObservation(
                            "005930",
                            "005930",
                            Market.DOMESTIC,
                            new BigDecimal("313000"),
                            1L,
                            1L,
                            LocalTime.NOON,
                            null,
                            KisFrameFixtures.TRACE_ID));
            handler.onQuote(
                    new QuoteObservation(
                            "005930",
                            "005930",
                            Market.DOMESTIC,
                            "H0STASP0",
                            "005930^1",
                            KisFrameFixtures.TRACE_ID));
            handler.onSignal(new SignalPayloadParser().parse(KisFrameFixtures.signalEntry()));

            assertThat(handler).isNotNull();
        }
    }

    @Nested
    @DisplayName("E6 — 소비 활성화 플래그 off")
    class DisabledFlag {

        @Test
        @DisplayName("enabled=false면 러너 빈이 등록되지 않고 컨텍스트는 정상 기동한다")
        void disabledFlagSkipsRunner() {
            runner.withPropertyValues("notifier.stream.enabled=false")
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                assertThat(context).doesNotHaveBean(StreamConsumerRunner.class);
                            });
        }

        @Test
        @DisplayName("enabled 기본값(미지정)에서는 러너 빈이 등록된다")
        void enabledByDefault() {
            runner.run(context -> assertThat(context).hasSingleBean(StreamConsumerRunner.class));
        }
    }

    /** FILTER-001 구현체를 흉내 내는 테스트용 핸들러. */
    static class CustomHandler implements StreamObservationHandler {

        @Override
        public void onTradeTick(TradeTickObservation observation) {
            // 배선 검증 전용 — 동작 없음.
        }

        @Override
        public void onQuote(QuoteObservation observation) {
            // 배선 검증 전용 — 동작 없음.
        }

        @Override
        public void onSignal(SignalObservation observation) {
            // 배선 검증 전용 — 동작 없음.
        }
    }

    /**
     * {@code @Configuration}을 붙이지 않는다 — 테스트 소스도 {@code com.aaa.notifier} 패키지에 있어서, 붙이면
     * {@code @SpringBootTest}의 컴포넌트 스캔이 이 클래스를 주워 담아 모든 통합 테스트 컨텍스트에 핸들러 빈이 하나 더 생긴다(실측: {@code
     * NoUniqueBeanDefinitionException: found 2: customHandler,recordingHandler}). {@code
     * withUserConfiguration}은 lite 모드로 {@code @Bean} 메서드를 처리하므로 애노테이션이 필요 없다.
     */
    static class CustomHandlerConfiguration {

        @Bean
        CustomHandler customHandler() {
            return new CustomHandler();
        }
    }
}
