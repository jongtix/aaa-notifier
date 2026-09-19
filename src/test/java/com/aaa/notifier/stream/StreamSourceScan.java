package com.aaa.notifier.stream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code com.aaa.notifier.stream} 프로덕션 소스 정적 스캔 헬퍼 (AC-11/AC-13/AC-14/AC-16 정적 단언 공용).
 *
 * <p>클래스파일이 아니라 <b>소스 원문</b>을 스캔한다 — 금지 대상이 "배율 연산 호출"·"문자열 리터럴"처럼 컴파일 후 소실되거나 상수 폴딩되는 형태라서, 바이트코드
 * 스캔으로는 잡히지 않거나 오탐이 생긴다.
 *
 * <p><b>주석은 스캔 전에 제거한다.</b> 금지 대상을 <em>설명하는</em> javadoc(예: "stripTrailingZeros를 거치면 scale이 줄어든다")이
 * 위반으로 오탐되면, 가드를 통과시키려고 근거 문서를 지우게 된다 — 가드가 문서를 적대시하는 구조는 그 자체로 결함이다. 따라서 검사 대상은 코드뿐이다.
 *
 * <p>주석 제거는 정규식 기반이라 문자열 리터럴 안의 {@code //}를 주석으로 오인할 수 있다. 현재 스트림 패키지에는 그런 리터럴이 없으며(URL 미사용), 생기면 이
 * 헬퍼를 토크나이저 기반으로 교체해야 한다.
 */
final class StreamSourceScan {

    private static final Path PRODUCTION_SOURCE_DIR =
            Path.of("src", "main", "java", "com", "aaa", "notifier", "stream");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    private StreamSourceScan() {}

    /** 스트림 패키지의 프로덕션 소스 파일 전체를 읽어 (파일명, 원문) 쌍으로 돌려준다. */
    static List<SourceFile> productionSources() {
        try (Stream<Path> paths = Files.walk(PRODUCTION_SOURCE_DIR)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(StreamSourceScan::read)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "스트림 패키지 프로덕션 소스를 읽을 수 없다: " + PRODUCTION_SOURCE_DIR.toAbsolutePath(), e);
        }
    }

    /** 주어진 토큰 중 하나라도 포함한 소스 파일의 "파일명:토큰" 목록을 돌려준다(위반 목록). */
    static List<String> findOccurrences(List<String> forbiddenTokens) {
        return productionSources().stream()
                .flatMap(
                        source ->
                                forbiddenTokens.stream()
                                        .filter(token -> source.content().contains(token))
                                        .map(token -> source.fileName() + ":" + token))
                .toList();
    }

    private static SourceFile read(Path path) {
        try {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            return new SourceFile(path.getFileName().toString(), stripComments(source));
        } catch (IOException e) {
            throw new UncheckedIOException("소스 파일을 읽을 수 없다: " + path, e);
        }
    }

    private static String stripComments(String source) {
        String withoutBlocks = BLOCK_COMMENT.matcher(source).replaceAll(" ");
        return LINE_COMMENT.matcher(withoutBlocks).replaceAll(" ");
    }

    /** 스캔 대상 소스 파일 1건 (주석 제거 후 본문). */
    record SourceFile(String fileName, String content) {}
}
