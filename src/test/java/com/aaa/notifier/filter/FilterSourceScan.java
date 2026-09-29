package com.aaa.notifier.filter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code com.aaa.notifier.filter} 프로덕션 소스 정적 스캔 헬퍼 (AC-3 ③·AC-21·AC-23 정적 단언 공용).
 *
 * <p>스트림 패키지의 {@code StreamSourceScan}과 같은 방식이다 — 소스 원문을 읽되 주석을 먼저 제거한다. 금지 대상을 <em>설명하는</em>
 * javadoc이 위반으로 오탐되면 가드를 통과시키려고 근거 문서를 지우게 되기 때문이다.
 */
final class FilterSourceScan {

    private static final Path PRODUCTION_SOURCE_DIR =
            Path.of("src", "main", "java", "com", "aaa", "notifier", "filter");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    private FilterSourceScan() {}

    /** 주어진 토큰 중 하나라도 포함한 소스 파일의 "파일명:토큰" 목록(위반 목록). */
    static List<String> findOccurrences(List<String> forbiddenTokens) {
        try (Stream<Path> paths = Files.walk(PRODUCTION_SOURCE_DIR)) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                    .flatMap(
                            path -> {
                                String code = stripComments(read(path));
                                return forbiddenTokens.stream()
                                        .filter(code::contains)
                                        .map(token -> path.getFileName() + ":" + token);
                            })
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "필터 패키지 소스를 읽을 수 없다: " + PRODUCTION_SOURCE_DIR.toAbsolutePath(), e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("소스 파일을 읽을 수 없다: " + path, e);
        }
    }

    private static String stripComments(String source) {
        String withoutBlocks = BLOCK_COMMENT.matcher(source).replaceAll(" ");
        return LINE_COMMENT.matcher(withoutBlocks).replaceAll(" ");
    }
}
