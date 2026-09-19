package com.aaa.notifier.stream;

import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 소비 계층 빈 배선 (REQ-NOTIFIER-CONSUMER-003/051/052).
 *
 * <p><b>일반 {@code @Configuration}이 아니라 {@code @AutoConfiguration}인 이유</b>는 두 가지다. 첫째, 일반
 * {@code @Configuration}은 auto-configuration보다 <em>먼저</em> 처리되므로
 * {@code @ConditionalOnBean(StringRedisTemplate.class)}가 항상 실패한다(FOUNDATION-001의 {@code
 * RedisHealthConfig}가 같은 함정을 실측으로 확인했다). 둘째, {@code @ConditionalOnMissingBean}으로 FILTER-001의 핸들러
 * 구현체에 자리를 내주려면 사용자 빈이 먼저 등록된 뒤에 평가되어야 하는데, 그 순서를 보장하는 것이 auto-configuration이다.
 *
 * <p>{@code @ConditionalOnBean(StringRedisTemplate.class)}는 smoke 프로파일(RedisAutoConfiguration 제외)에서
 * 소비 계층 전체가 조용히 비활성화되게 한다 — 이 조건이 없으면 Redis 없는 컨텍스트의 기동 자체가 깨진다.
 */
@Slf4j
@AutoConfiguration(after = RedisAutoConfiguration.class)
@EnableConfigurationProperties(StreamConsumerProperties.class)
@ConditionalOnBean(StringRedisTemplate.class)
public class StreamConsumerAutoConfiguration {

    /** 엔트리를 관측치로 변환하는 틱 파서. */
    @Bean
    @ConditionalOnMissingBean
    public TickPayloadParser tickPayloadParser() {
        return new TickPayloadParser();
    }

    /** 엔트리를 관측치로 변환하는 신호 파서. */
    @Bean
    @ConditionalOnMissingBean
    public SignalPayloadParser signalPayloadParser() {
        return new SignalPayloadParser();
    }

    /** DLQ 이관기. */
    @Bean
    @ConditionalOnMissingBean
    public DeadLetterPublisher deadLetterPublisher(StringRedisTemplate redisTemplate) {
        return new DeadLetterPublisher(redisTemplate);
    }

    /**
     * 하류 핸들러의 무동작 기본 구현 (REQ-051).
     *
     * <p>FILTER-001이 {@link StreamObservationHandler} 구현체를 빈으로 올리면 이 기본 구현은 물러난다. 기본 구현이 있어야 이 계층이
     * FILTER-001 없이도 단독 배포·운용 가능하다.
     */
    @Bean
    @ConditionalOnMissingBean(StreamObservationHandler.class)
    public StreamObservationHandler noOpStreamObservationHandler() {
        return new NoOpStreamObservationHandler();
    }

    /**
     * 스트림당 소비 워커 1개.
     *
     * @param properties 소비 설정
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "notifier.stream",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public StreamConsumerRunner streamConsumerRunner(
            StringRedisTemplate redisTemplate,
            StreamConsumerProperties properties,
            TickPayloadParser tickPayloadParser,
            SignalPayloadParser signalPayloadParser,
            StreamObservationHandler handler,
            DeadLetterPublisher deadLetterPublisher) {
        List<StreamConsumerWorker> workers =
                Arrays.stream(ConsumedStream.values())
                        .map(
                                stream ->
                                        new StreamConsumerWorker(
                                                stream,
                                                redisTemplate,
                                                properties,
                                                tickPayloadParser,
                                                signalPayloadParser,
                                                handler,
                                                deadLetterPublisher))
                        .toList();
        return new StreamConsumerRunner(redisTemplate, workers);
    }

    /**
     * 관측치를 소모하고 계측만 남기는 무동작 핸들러.
     *
     * <p>DEBUG 로그만 남기는 이유는, 장중 틱이 초당 수십 건 도착하는데 INFO로 남기면 로그가 그것만으로 가득 차기 때문이다.
     */
    static class NoOpStreamObservationHandler implements StreamObservationHandler {

        @Override
        public void onTradeTick(TradeTickObservation observation) {
            if (log.isDebugEnabled()) {
                log.debug(
                        "[stream-noop] 체결 관측치 수신 symbol={} price={}",
                        observation.symbol(),
                        observation.price());
            }
        }

        @Override
        public void onQuote(QuoteObservation observation) {
            if (log.isDebugEnabled()) {
                log.debug(
                        "[stream-noop] 호가 패스스루 수신 symbol={} trId={}",
                        observation.symbol(),
                        observation.transactionId());
            }
        }

        @Override
        public void onSignal(SignalObservation observation) {
            if (log.isDebugEnabled()) {
                log.debug(
                        "[stream-noop] 신호 관측치 수신 symbol={} horizon={}",
                        observation.symbol(),
                        observation.horizon());
            }
        }
    }
}
