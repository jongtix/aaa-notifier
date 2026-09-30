package com.aaa.notifier.telegram;

import com.aaa.notifier.filter.AlertDecisionSink;
import com.aaa.notifier.filter.FilterAutoConfiguration;
import com.aaa.notifier.filter.FilterMetrics;
import com.aaa.notifier.filter.JdbcDryRunAlertDecisionSink;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 매매봇 실발송 빈 배선 (SPEC-NOTIFIER-TELEGRAM-001 plan.md §D.1).
 *
 * <p><b>실발송 모드가 꺼져 있으면(기본값) 이 구성 전체가 평가되지 않는다</b> — FILTER-001의 DRYRUN 싱크가 그대로 쓰이고 텔레그램·대기
 * 큐·safe_mode·{@code stream:alert} 어디에도 손대지 않으며 이 구성의 계측도 등록되지 않는다(REQ-001, REQ-052 게이트).
 *
 * <p><b>{@code FilterAutoConfiguration}보다 먼저 평가되어야 한다</b> — 그쪽의
 * {@code @ConditionalOnMissingBean(AlertDecisionSink)} 기본값이 이 구성의 실발송 싱크에 자리를 내주려면 이 빈 정의가 먼저 등록돼
 * 있어야 한다. {@code FilterMetrics}·{@code Clock}은 그쪽이 등록하므로 빈 정의가 아닌 생성 시점에 주입받는다. 필터 파이프라인이 꺼져 있으면
 * 결정이 나오지 않으므로 실발송 구성도 켜지지 않는다.
 *
 * <p>kill switch 동안 쓰는 DRYRUN 싱크는 빈이 아니라 이 구성 안의 내부 인스턴스다 — {@code AlertDecisionSink} 빈이 둘이 되면
 * 파이프라인 주입이 모호해진다.
 */
@AutoConfiguration(
        after = {RedisAutoConfiguration.class, JdbcTemplateAutoConfiguration.class},
        before = FilterAutoConfiguration.class)
@ConditionalOnBean({StringRedisTemplate.class, JdbcTemplate.class})
@ConditionalOnProperty(prefix = "notifier.telegram", name = "enabled", havingValue = "true")
@ConditionalOnProperty(
        prefix = "notifier.filter",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(TelegramProperties.class)
public class TelegramAutoConfiguration {

    /**
     * 기동 설정 검증 (REQ-003) — 구성 인스턴스 생성 시점에 검증하므로 설정 누락은 어떤 실발송 빈보다 먼저 드러난다.
     *
     * @param properties 바인딩된 설정
     */
    public TelegramAutoConfiguration(TelegramProperties properties) {
        properties.requireCredentials();
    }

    @Bean
    TelegramMetrics telegramMetrics(MeterRegistry meterRegistry) {
        return TelegramMetrics.create(meterRegistry);
    }

    @Bean
    TelegramBotClient telegramBotClient(TelegramProperties properties) {
        return new TelegramBotClient(
                properties.baseUrl(),
                properties.botToken(),
                properties.chatId(),
                properties.connectTimeout(),
                properties.readTimeout());
    }

    @Bean
    RedisSafeModeStore telegramSafeModeStore(StringRedisTemplate redis, TelegramMetrics metrics) {
        return new RedisSafeModeStore(redis, metrics);
    }

    @Bean
    RedisPendingQueue telegramPendingQueue(
            StringRedisTemplate redis, TelegramProperties properties) {
        return new RedisPendingQueue(redis, properties.queue().retention());
    }

    @Bean
    TelegramDispatcher telegramDispatcher(
            TelegramProperties properties,
            TelegramBotClient client,
            RedisSafeModeStore safeMode,
            RedisPendingQueue queue,
            StringRedisTemplate redis,
            JdbcTemplate jdbcTemplate,
            TelegramMetrics metrics,
            FilterMetrics filterMetrics,
            Clock clock) {
        DeliveryContext context =
                new DeliveryContext(
                        client,
                        safeMode,
                        queue,
                        new JdbcNotificationLog(jdbcTemplate, metrics, clock),
                        new RedisAlertPublisher(redis, properties.alertStreamMaxLen(), metrics),
                        new JdbcDryRunAlertDecisionSink(jdbcTemplate, filterMetrics, clock),
                        new TelegramMessageFormatter(),
                        metrics,
                        filterMetrics,
                        clock,
                        Sleeper.THREAD,
                        DeliveryPolicy.from(properties));
        return new TelegramDispatcher(context, properties.dispatchBufferCapacity());
    }

    @Bean
    SafeModeProbe telegramSafeModeProbe(
            RedisSafeModeStore safeMode,
            TelegramBotClient client,
            RedisPendingQueue queue,
            TelegramDispatcher dispatcher,
            TelegramMetrics metrics) {
        return new SafeModeProbe(safeMode, client, queue, dispatcher, metrics);
    }

    /** 결정 싱크 실발송 구현 — FILTER-001의 {@code @ConditionalOnMissingBean} DRYRUN 기본값을 대신한다. */
    @Bean
    AlertDecisionSink telegramAlertDecisionSink(
            TelegramDispatcher dispatcher, FilterMetrics filterMetrics) {
        return new TelegramAlertDecisionSink(dispatcher, filterMetrics);
    }
}
