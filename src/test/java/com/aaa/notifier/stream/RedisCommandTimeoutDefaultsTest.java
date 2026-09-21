package com.aaa.notifier.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * 운영 기본 설정의 Redis 명령 타임아웃 검증 (REQ-NOTIFIER-CONSUMER-014, AC-8 ③).
 *
 * <p>Lettuce 기본 명령 타임아웃(60s)이 그대로면 연결 두절 중 소비 호출이 재전송 대기에 묶여 오류 로그가 최대 60초 늦게 남는다. 이 테스트는 {@code
 * test} 프로파일이나 개별 테스트의 {@code spring.data.redis.timeout} 오버라이드 없이 <b>{@code application.yml}
 * 그대로</b>를 읽어, 프로덕션 기본값이 짧게 명시돼 있음을 확인한다. 실 Redis는 필요 없다 — 연결 팩토리는 첫 명령 전까지 접속하지 않는다.
 */
@DisplayName("Redis 명령 타임아웃 — 운영 기본 설정 (REQ-014, AC-8 ③)")
class RedisCommandTimeoutDefaultsTest {

    private static final Duration EXPECTED_COMMAND_TIMEOUT = Duration.ofSeconds(5);

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                    // application.yml의 환경변수 자리만 채운다. 타임아웃은 건드리지 않는다.
                    .withPropertyValues(
                            "REDIS_HOST=localhost",
                            "REDIS_PORT=6379",
                            "REDIS_APPUSER_USERNAME=appuser",
                            "REDIS_APPUSER_PASSWORD=unused");

    @Test
    @DisplayName("application.yml 기본값이 Redis 명령 타임아웃을 짧게 명시한다 (Lettuce 기본 60s 아님)")
    void defaultConfiguration_appliesShortCommandTimeout() {
        runner.run(
                context ->
                        assertThat(
                                        context.getBean(LettuceConnectionFactory.class)
                                                .getClientConfiguration()
                                                .getCommandTimeout())
                                .isEqualTo(EXPECTED_COMMAND_TIMEOUT));
    }

    @Test
    @DisplayName("명령 타임아웃은 XREADGROUP 블로킹 대기 시간보다 길다 (아니면 정상 폴링이 매번 타임아웃된다)")
    void commandTimeout_exceedsBlockTimeout() {
        runner.run(
                context -> {
                    Duration commandTimeout =
                            context.getBean(LettuceConnectionFactory.class)
                                    .getClientConfiguration()
                                    .getCommandTimeout();
                    Duration blockTimeout =
                            Binder.get(context.getEnvironment())
                                    .bind("notifier.stream.block-timeout", Duration.class)
                                    .orElse(null);

                    assertThat(blockTimeout).isNotNull();
                    assertThat(commandTimeout).isGreaterThan(blockTimeout);
                });
    }
}
