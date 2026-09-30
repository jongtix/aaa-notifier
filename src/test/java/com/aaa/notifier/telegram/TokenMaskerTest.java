package com.aaa.notifier.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TokenMasker — 토큰 앞 4자 외 마스킹 (REQ-051, ADR-011 FRONT_ONLY)")
class TokenMaskerTest {

    private static final String TOKEN = "123456789:TESTSECRETVALUE";

    @Test
    @DisplayName("토큰은 앞 4자만 남기고 나머지를 가린다")
    void mask_keepsFrontFourOnly() {
        assertThat(TokenMasker.mask(TOKEN)).isEqualTo("1234****");
    }

    @Test
    @DisplayName("4자 이하·빈 값·null도 원문을 드러내지 않는다")
    void mask_shortOrBlank() {
        assertThat(TokenMasker.mask("abc")).isEqualTo("****");
        assertThat(TokenMasker.mask("")).isEqualTo("****");
        assertThat(TokenMasker.mask(null)).isEqualTo("****");
    }

    @Test
    @DisplayName("문장 안의 토큰 원문·비밀 부분·URL 인코딩 형태를 모두 가린다")
    void scrub_replacesEveryForm() {
        String text =
                "I/O error on POST https://api.telegram.org/bot"
                        + TOKEN
                        + "/sendMessage | secret=TESTSECRETVALUE | enc=123456789%3ATESTSECRETVALUE";

        String scrubbed = TokenMasker.scrub(text, TOKEN);

        assertThat(scrubbed).doesNotContain("TESTSECRETVALUE").contains("bot1234****/sendMessage");
    }

    @Test
    @DisplayName("null 문장은 빈 문자열, 토큰이 비어 있으면 문장을 그대로 둔다")
    void scrub_nullAndBlank() {
        assertThat(TokenMasker.scrub(null, TOKEN)).isEmpty();
        assertThat(TokenMasker.scrub("plain", "")).isEqualTo("plain");
    }
}
