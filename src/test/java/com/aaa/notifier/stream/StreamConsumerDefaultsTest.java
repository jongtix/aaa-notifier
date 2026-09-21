package com.aaa.notifier.stream;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 운영 기본 설정({@code application.yml})의 소비 계층 값 검증 — TECHSPEC §5.1 정합.
 *
 * <p>{@code test} 프로파일이나 개별 테스트의 오버라이드 없이 <b>{@code application.yml} 그대로</b>를 읽는다. 통합 테스트는 값을 짧게 줄여
 * 쓰므로 운영 기본값이 문서와 어긋나도 잡아 주지 못한다 — 그 사각을 이 테스트가 메운다(컨테이너 불필요).
 */
@DisplayName("소비 계층 운영 기본 설정 (application.yml, TECHSPEC §5.1)")
class StreamConsumerDefaultsTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    // application.yml의 환경변수 자리만 채운다. notifier.stream.* 값은 건드리지 않는다.
                    .withPropertyValues(
                            "REDIS_HOST=localhost",
                            "REDIS_PORT=6379",
                            "REDIS_APPUSER_USERNAME=appuser",
                            "REDIS_APPUSER_PASSWORD=unused");

    @Test
    @DisplayName("DLQ 상한 기본값은 500이다 (TECHSPEC §5.1 — stream:dlq:{stream명} MAXLEN 500)")
    void dlqMaxLen_defaultsTo500() {
        runner.run(
                context ->
                        assertThat(
                                        Binder.get(context.getEnvironment())
                                                .bind("notifier.stream.dlq-max-len", Long.class)
                                                .orElse(null))
                                .isEqualTo(500L));
    }
}
