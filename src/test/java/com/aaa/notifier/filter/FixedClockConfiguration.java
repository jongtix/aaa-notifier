package com.aaa.notifier.filter;

import java.time.Clock;
import java.time.ZonedDateTime;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 필터 통합 테스트 시계 — 2026-09-29(화) 10:00 KST(국내 정규장 중, 개장 후 60분).
 *
 * <p>{@link FilterAutoConfiguration#filterClock()}은 {@code @ConditionalOnMissingBean}이라 이 빈이 대체한다.
 * 픽스처 밴드 기준일 ({@link FilterDbFixtures#PREV_DAY})이 이 시계의 전일이다.
 */
@TestConfiguration
class FixedClockConfiguration {

    static final ZonedDateTime NOW = ZonedDateTime.of(2026, 9, 29, 10, 0, 0, 0, MarketSession.KST);

    @Bean
    Clock fixedFilterClock() {
        return Clock.fixed(NOW.toInstant(), MarketSession.KST);
    }
}
