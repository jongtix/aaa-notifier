package com.aaa.notifier.filter;

import com.aaa.notifier.stream.StreamConsumerAutoConfiguration;
import com.aaa.notifier.stream.StreamObservationHandler;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 필터 파이프라인 빈 배선 (SPEC-NOTIFIER-FILTER-001).
 *
 * <p><b>{@code @AutoConfiguration}인 이유</b>는 소비 계층({@link StreamConsumerAutoConfiguration})과 같다 —
 * {@code RedisAutoConfiguration}·{@code JdbcTemplateAutoConfiguration} 이후에 평가되어야
 * {@code @ConditionalOnBean}이 성립하고, 소비 계층보다 <b>먼저</b> 평가되어야 그쪽의
 * {@code @ConditionalOnMissingBean(StreamObservationHandler)} 무동작 기본값이 이 파이프라인에 자리를 내준다.
 *
 * <p>Redis 또는 DataSource가 없는 컨텍스트(smoke 프로파일)에서는 조용히 비활성화된다.
 */
@AutoConfiguration(
        after = {RedisAutoConfiguration.class, JdbcTemplateAutoConfiguration.class},
        before = StreamConsumerAutoConfiguration.class)
@ConditionalOnBean({StringRedisTemplate.class, JdbcTemplate.class})
@ConditionalOnProperty(
        prefix = "notifier.filter",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(FilterProperties.class)
public class FilterAutoConfiguration {

    /** 시장 시각·상태 TTL 계산용 시계. 테스트는 고정 시계 빈으로 교체한다. */
    @Bean
    @ConditionalOnMissingBean
    public Clock filterClock() {
        return Clock.system(MarketSession.KST);
    }

    @Bean
    @ConditionalOnMissingBean
    public FilterMetrics filterMetrics(MeterRegistry meterRegistry) {
        return FilterMetrics.create(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReferenceDataHolder referenceDataHolder() {
        return new ReferenceDataHolder();
    }

    @Bean
    @ConditionalOnMissingBean
    public FilterStateStore filterStateStore(StringRedisTemplate redisTemplate) {
        return new RedisFilterStateStore(redisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReferenceDataLoader referenceDataLoader(
            JdbcTemplate jdbcTemplate, FilterProperties properties) {
        return new ReferenceDataLoader(jdbcTemplate, properties.referenceLoad().lookbackDays());
    }

    @Bean
    @ConditionalOnMissingBean
    public ReferenceDataRefresher referenceDataRefresher(
            ReferenceDataLoader loader,
            ReferenceDataHolder holder,
            FilterStateStore stateStore,
            FilterMetrics metrics,
            Clock clock) {
        return new ReferenceDataRefresher(loader, holder, stateStore, metrics, clock);
    }

    /** 가드 → 확증 → 쿨다운 → 결정 방출 게이트. */
    @Bean
    @ConditionalOnMissingBean
    public TransitionGate transitionGate(
            FilterStateStore stateStore,
            AlertDecisionSink alertDecisionSink,
            FilterMetrics metrics,
            FilterProperties properties) {
        return new TransitionGate(
                stateStore,
                new GuardEvaluator(properties.guard(), metrics),
                new TransitionPolicy(properties.confirm(), properties.cooldown()),
                new ConfidencePolicy(properties.confidence()),
                alertDecisionSink,
                metrics);
    }

    /**
     * 결정 싱크 기본 구현 — {@code notification_log} DRYRUN INSERT (REQ-062). TELEGRAM-001의 실발송 구현체가 올라오면
     * 물러난다.
     */
    @Bean
    @ConditionalOnMissingBean(AlertDecisionSink.class)
    public AlertDecisionSink jdbcDryRunAlertDecisionSink(
            JdbcTemplate jdbcTemplate, FilterMetrics metrics, Clock clock) {
        return new JdbcDryRunAlertDecisionSink(jdbcTemplate, metrics, clock);
    }

    /**
     * 소비 계층 하류 포트의 실동작 구현 (REQ-062 — CONSUMER-001 REQ-051의 무동작 기본값을 코드 변경 없이 대체).
     *
     * <p>테스트 등에서 다른 {@link StreamObservationHandler}가 이미 등록되어 있으면 물러난다.
     */
    @Bean
    @ConditionalOnMissingBean(StreamObservationHandler.class)
    public FilterPipeline filterPipeline(
            ReferenceDataHolder holder,
            FilterStateStore stateStore,
            TransitionGate transitionGate,
            FilterMetrics metrics,
            FilterProperties properties,
            Clock clock) {
        return new FilterPipeline(
                holder,
                stateStore,
                new IntradayTracker(),
                transitionGate,
                new ConfidencePolicy(properties.confidence()),
                metrics,
                clock);
    }
}
