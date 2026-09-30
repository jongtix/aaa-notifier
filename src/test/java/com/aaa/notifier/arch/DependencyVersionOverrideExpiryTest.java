package com.aaa.notifier.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code gradle/libs.versions.toml}의 {@code jackson2Bom} 버전 덮어쓰기(CVE-2026-68497 대응, 2026-09-30)가
 * 방치되지 않도록 강제하는 만료 게이트.
 *
 * <p>Spring Boot 3.5.16(3.5.x 최신)이 관리하는 jackson-databind 버전(2.21.4)에 unbounded numeric parsing DoS
 * HIGH CVE가 있어 카탈로그에서 직접 상위 버전(2.21.6)을 명시하고 {@code build.gradle.kts}가 {@code
 * extra["jackson-bom.version"]}로 소비한다(실측: {@code jackson-2-bom.version}은 무효, {@code
 * jackson-bom.version} 단독으로 2.21.6 확정 — aaa-collector의 tomcat/netty 오버라이드와 동일한 문제 구조). 이 덮어쓰기는
 * Spring Boot의 다음 패치 릴리스가 같은 버전을 흡수하면 불필요해지는데, 그 시점을 누구도 능동적으로 알려주지 않으므로 방치되면 영구 고착된다. 이 테스트는
 * {@link #REVIEW_BY} 이후 {@code ./gradlew test}/{@code check}를 실제로 실패시켜 CI에서 검토를 강제한다.
 *
 * <p><b>만료 시 조치</b>: Spring Boot 최신판이 jackson-databind ≥ 2.21.6을 관리하는지 확인한다. 흡수했다면 {@code
 * gradle/libs.versions.toml}의 {@code jackson2Bom} 항목, {@code build.gradle.kts}의 덮어쓰기 블록, 이 테스트 클래스를
 * 함께 삭제한다. 아직 미흡수라면 {@link #REVIEW_BY}를 새 날짜로 갱신한다.
 */
class DependencyVersionOverrideExpiryTest {

    private static final LocalDate REVIEW_BY = LocalDate.of(2026, 12, 30);

    @Test
    @DisplayName("jackson-2-bom 버전 덮어쓰기 검토 기한이 지나지 않아야 한다")
    void jacksonBomVersionOverrideMustBeReviewedBeforeExpiry() {
        assertThat(LocalDate.now())
                .as(
                        "gradle/libs.versions.toml의 jackson2Bom 버전 덮어쓰기(CVE-2026-68497 대응) 검토 "
                                + "기한이 지났다. Spring Boot 최신판이 이 CVE를 흡수했는지 확인하고, 흡수했다면 "
                                + "카탈로그의 jackson2Bom 항목, build.gradle.kts의 덮어쓰기 블록, 이 테스트를 함께 "
                                + "삭제한다. 아직 미흡수이면 REVIEW_BY를 갱신한다.")
                .isBefore(REVIEW_BY);
    }
}
