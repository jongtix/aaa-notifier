package com.aaa.notifier.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 범위 경계 정적 가드 (REQ-NOTIFIER-CONSUMER-052, AC-6 ②, AC-13 ③, AC-16 ②③④⑤).
 *
 * <p>이 SPEC은 소비 계층에서 끝난다. 밴드 판정·히스테리시스·쿨다운·텔레그램 발송·{@code notification_log} 쓰기·{@code stream:alert}
 * 발행은 전부 FILTER-001/TELEGRAM-001 소관이며, DB 배선은 SCHEMA-001이 이연한 상태 그대로 둔다.
 *
 * <p>경계는 문서가 아니라 <b>빌드 게이트</b>로 지킨다 — "잠깐만 {@code filter:} 키 하나만" 하는 순간 FILTER-001의 설계 공간이 조용히 좁아지기
 * 때문이다.
 */
@DisplayName("스트림 패키지 범위 경계 정적 가드 (REQ-052, AC-6/AC-13/AC-16)")
class StreamBoundaryGuardTest {

    @Nested
    @DisplayName("AC-16 ②③④ — 필터·DB·알림 심볼 미참조")
    class ForbiddenSymbols {

        @Test
        @DisplayName("② 문자열 리터럴 `filter:`가 0건이다")
        void noFilterKeyLiteral() {
            assertThat(StreamSourceScan.findOccurrences(List.of("filter:")))
                    .as("`filter:*` 키 접근은 FILTER-001 소관이다 (spec.md §3)")
                    .isEmpty();
        }

        @Test
        @DisplayName("③ JPA/DataSource 타입 참조가 0건이다")
        void noPersistenceTypes() {
            assertThat(
                            StreamSourceScan.findOccurrences(
                                    List.of(
                                            "javax.sql.DataSource",
                                            "jakarta.persistence",
                                            "org.springframework.data.jpa")))
                    .as("notifier의 DB 배선은 SCHEMA-001 REQ-041이 계속 이연한 상태다")
                    .isEmpty();
        }

        @Test
        @DisplayName("④ `notification_log`·`stream:alert` 문자열이 0건이다")
        void noDownstreamWriteTargets() {
            assertThat(
                            StreamSourceScan.findOccurrences(
                                    List.of("notification_log", "stream:alert")))
                    .as("notification_log INSERT와 stream:alert 발행은 TELEGRAM-001 소관이다")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("AC-6 ② — 고정지연 폴링 부재")
    class NoScheduledPolling {

        @Test
        @DisplayName("스트림 패키지 소스에 `@Scheduled` 애노테이션이 0건이다")
        void noScheduledAnnotation() {
            assertThat(StreamSourceScan.findOccurrences(List.of("@Scheduled")))
                    .as("스트림 소비는 블로킹 XREADGROUP 루프이지 @Scheduled 잡이 아니다 (ADR-008, REQ-012 후단)")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("AC-13 ③ — 호가 필드 인덱스 상수 부재")
    class NoQuoteFieldIndices {

        @Test
        @DisplayName("호가 고유 필드명이 소스에 나타나지 않는다 (62·71필드 선제 파싱 금지)")
        void noQuoteFieldNames() {
            // 레벨별 호가·잔량 필드명. 하나라도 나타나면 호가를 필드 단위로 해석하기 시작했다는 뜻이다.
            List<String> quoteFieldNames =
                    List.of(
                            "ASKP1",
                            "BIDP1",
                            "ASKP_RSQN",
                            "BIDP_RSQN",
                            "TOTAL_ASKP_RSQN",
                            "TOTAL_BIDP_RSQN",
                            "PBID",
                            "PASK",
                            "VBID",
                            "VASK");

            assertThat(StreamSourceScan.findOccurrences(quoteFieldNames))
                    .as("호가 해석은 소비 규칙이 정의되는 FILTER-001로 이연한다 (spec.md HISTORY [D-C3])")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("AC-16 ⑤ — 빌드 의존성 무변경")
    class BuildDependencies {

        @Test
        @DisplayName("build.gradle.kts에 JPA·MySQL·Flyway 의존성이 없다")
        void buildFile_hasNoPersistenceDependencies() {
            String build = read(Path.of("build.gradle.kts"));

            assertThat(build)
                    .as("DB 접근 배선은 notifier가 실제로 DB에 접속하는 시점까지 이연한다 (REQ-052)")
                    .doesNotContain("data-jpa")
                    .doesNotContain("mysql")
                    .doesNotContain("flyway");
        }

        @Test
        @DisplayName("libs.versions.toml에 신규 스트림 전용 의존성이 없다 (기존 Redis 스타터로 충분)")
        void versionCatalog_hasNoNewStreamDependency() {
            String catalog = read(Path.of("gradle", "libs.versions.toml"));

            assertThat(catalog)
                    .as("소비 계층은 spring-boot-starter-data-redis만으로 구현된다 (plan.md §E)")
                    .doesNotContain("redisson")
                    .doesNotContain("lettuce-core");
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("파일을 읽을 수 없다: " + path.toAbsolutePath(), e);
        }
    }
}
