package com.aaa.notifier.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.aaa.notifier.filter.AlertDecisionSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 실발송 자동 구성의 활성화 게이트·기동 설정 검증 (REQ-001 게이트 부분, REQ-003 — AC-01 ⑤, AC-03).
 *
 * <p>{@link ApplicationContextRunner}로 자동 구성만 평가하므로 컨테이너 없이 단위 계층에서 돈다.
 */
@DisplayName("TelegramAutoConfiguration — 활성화 게이트·기동 설정 검증 (REQ-001, REQ-003)")
class TelegramAutoConfigurationTest {

    private static final String TOKEN = "123456789:TESTSECRETVALUE";
    private static final String CHAT_ID = "987654321";

    private final ApplicationContextRunner runner =
            TelegramTestSupport.contextRunner()
                    .withConfiguration(AutoConfigurations.of(TelegramAutoConfiguration.class))
                    .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                    .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));

    @Test
    @DisplayName("AC-01 ⑤ — 실발송 모드 설정이 없으면(기본값 false) 실발송 빈이 하나도 등록되지 않는다")
    void disabledByDefault_registersNothing() {
        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TelegramProperties.class);
                });
    }

    @Test
    @DisplayName("AC-03 ③ — 실발송 모드가 꺼져 있으면 토큰·chat_id가 모두 비어 있어도 기동한다")
    void disabled_blankCredentials_startsUp() {
        runner.withPropertyValues(
                        "notifier.telegram.enabled=false",
                        "notifier.telegram.bot-token=",
                        "notifier.telegram.chat-id=")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Nested
    @DisplayName("실발송 모드 켜짐")
    class Enabled {

        private ApplicationContextRunner enabled() {
            return runner.withPropertyValues(TelegramTestSupport.enabledProperties());
        }

        @Test
        @DisplayName("AC-03 — 토큰이 비어 있으면 토큰 설정 키 이름을 밝히고 기동이 실패한다 (chat_id 값은 노출하지 않음)")
        void blankToken_failsNamingTokenKey() {
            enabled()
                    .withPropertyValues(
                            "notifier.telegram.bot-token=", "notifier.telegram.chat-id=" + CHAT_ID)
                    .run(
                            context -> {
                                assertThat(context).hasFailed();
                                String message = fullMessage(context.getStartupFailure());
                                assertThat(message)
                                        .contains("notifier.telegram.bot-token")
                                        .doesNotContain(CHAT_ID);
                            });
        }

        @Test
        @DisplayName("AC-03 ① ② — chat_id만 비어도 같은 방식으로 실패하고 토큰 값은 메시지에 없다")
        void blankChatId_failsNamingChatIdKeyWithoutToken() {
            enabled()
                    .withPropertyValues(
                            "notifier.telegram.bot-token=" + TOKEN, "notifier.telegram.chat-id=  ")
                    .run(
                            context -> {
                                assertThat(context).hasFailed();
                                String message = fullMessage(context.getStartupFailure());
                                assertThat(message)
                                        .contains("notifier.telegram.chat-id")
                                        .doesNotContain("TESTSECRETVALUE");
                            });
        }

        @Test
        @DisplayName("토큰·chat_id가 모두 있으면 기동하고 설정 빈이 등록된다")
        void credentialsPresent_startsUp() {
            enabled()
                    .withPropertyValues(
                            "notifier.telegram.bot-token=" + TOKEN,
                            "notifier.telegram.chat-id=" + CHAT_ID)
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                assertThat(context).hasSingleBean(TelegramProperties.class);
                            });
        }

        @Test
        @DisplayName("AC-02 / REQ-002 — 결정 싱크 빈은 실발송 구현 하나뿐이다 (FILTER-001 DRYRUN 싱크는 빈으로 등록되지 않음)")
        void enabled_registersSingleTelegramSink() {
            enabled()
                    .withPropertyValues(
                            "notifier.telegram.bot-token=" + TOKEN,
                            "notifier.telegram.chat-id=" + CHAT_ID)
                    .run(
                            context -> {
                                assertThat(context).hasSingleBean(AlertDecisionSink.class);
                                assertThat(context.getBean(AlertDecisionSink.class))
                                        .isInstanceOf(TelegramAlertDecisionSink.class);
                                assertThat(context)
                                        .hasSingleBean(TelegramDispatcher.class)
                                        .hasSingleBean(SafeModeProbe.class)
                                        .hasSingleBean(TelegramMetrics.class);
                            });
        }

        @Test
        @DisplayName("필터 파이프라인이 꺼져 있으면 실발송 구성도 켜지지 않는다")
        void filterDisabled_registersNothing() {
            enabled()
                    .withPropertyValues(
                            "notifier.filter.enabled=false",
                            "notifier.telegram.bot-token=" + TOKEN,
                            "notifier.telegram.chat-id=" + CHAT_ID)
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                assertThat(context).doesNotHaveBean(TelegramDispatcher.class);
                            });
        }

        @Test
        @DisplayName("REQ-051 — 설정 객체의 문자열 표현에 토큰 원문이 없다 (앞 4자 외 마스킹)")
        void propertiesToString_masksToken() {
            enabled()
                    .withPropertyValues(
                            "notifier.telegram.bot-token=" + TOKEN,
                            "notifier.telegram.chat-id=" + CHAT_ID)
                    .run(
                            context ->
                                    assertThat(context.getBean(TelegramProperties.class).toString())
                                            .doesNotContain("TESTSECRETVALUE")
                                            .contains("1234"));
        }
    }

    private static String fullMessage(Throwable failure) {
        StringBuilder builder = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            builder.append(t.getMessage()).append('\n');
        }
        return builder.toString();
    }
}
