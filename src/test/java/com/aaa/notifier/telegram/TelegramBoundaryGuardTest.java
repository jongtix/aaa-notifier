package com.aaa.notifier.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 실발송 패키지 정적 경계 가드 (REQ-012·051, AC-06 ②, AC-15 ③, spec.md §4 아키텍처 고정) — 소스·빌드 파일 스캔이라 컨테이너 없이 돈다.
 */
@DisplayName("실발송 정적 경계 가드 — 버튼 코드·시스템봇 토큰·봇 라이브러리 부재")
class TelegramBoundaryGuardTest {

    private static final Path MAIN = Path.of("src/main");

    private static List<String> occurrences(Path root, List<String> needles) throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                for (String needle : needles) {
                    if (content.contains(needle)) {
                        found.add(file.getFileName() + ":" + needle);
                    }
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("AC-06 ② — src/main에 인라인 키보드·callback_data를 만드는 코드가 없다 (Phase 4 이관)")
    void noInlineKeyboardCode() throws IOException {
        assertThat(
                        occurrences(
                                MAIN,
                                List.of("InlineKeyboardMarkup", "callback_data", "reply_markup\"")))
                .isEmpty();
    }

    @Test
    @DisplayName("AC-15 ③ — src/main에 시스템봇 토큰 참조가 없다 (TECHSPEC §1.4)")
    void noSystemBotToken() throws IOException {
        assertThat(occurrences(MAIN, List.of("TELEGRAM_SYSTEM_BOT_TOKEN"))).isEmpty();
    }

    @Test
    @DisplayName("[D-2] — 텔레그램 봇 라이브러리·WebClient(webflux) 의존성이 없다")
    void noBotLibraryOrWebClient() throws IOException {
        String catalog =
                Files.readString(Path.of("gradle/libs.versions.toml"), StandardCharsets.UTF_8);
        String build = Files.readString(Path.of("build.gradle.kts"), StandardCharsets.UTF_8);

        assertThat(catalog + build)
                .doesNotContainIgnoringCase("telegrambots")
                .doesNotContainIgnoringCase("pengrad")
                .doesNotContain("webflux");
        assertThat(occurrences(MAIN, List.of("import org.springframework.web.reactive"))).isEmpty();
    }
}
