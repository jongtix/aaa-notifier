package com.aaa.notifier.telegram;

import com.aaa.notifier.filter.FilterAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 매매봇 실발송 빈 배선 (SPEC-NOTIFIER-TELEGRAM-001 plan.md §D.1).
 *
 * <p><b>실발송 모드가 꺼져 있으면(기본값) 이 구성 전체가 평가되지 않는다</b> — FILTER-001의 DRYRUN 싱크가 그대로 쓰이고 텔레그램·대기
 * 큐·safe_mode·{@code stream:alert} 어디에도 손대지 않는다(REQ-001).
 *
 * <p><b>{@code FilterAutoConfiguration}보다 먼저 평가되어야 한다</b> — 그쪽의
 * {@code @ConditionalOnMissingBean(AlertDecisionSink)} 기본값이 이 구성의 실발송 싱크에 자리를 내주려면 이 빈 정의가 먼저 등록돼
 * 있어야 한다.
 */
@AutoConfiguration(
        after = {RedisAutoConfiguration.class, JdbcTemplateAutoConfiguration.class},
        before = FilterAutoConfiguration.class)
@ConditionalOnBean({StringRedisTemplate.class, JdbcTemplate.class})
@ConditionalOnProperty(prefix = "notifier.telegram", name = "enabled", havingValue = "true")
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
}
