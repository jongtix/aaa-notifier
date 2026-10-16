package com.aaa.notifier.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code gradle/libs.versions.toml}의 {@code tomcat}·{@code jackson3Bom} 버전 덮어쓰기(2026-10-16, Trivy
 * CRITICAL/HIGH 대응)가 방치되지 않도록 강제하는 만료 게이트.
 *
 * <p>Spring Boot 4.1.1 BOM이 관리하는 tomcat-embed-core 11.0.24(CVE-2026-65182 / CVE-2026-65905 /
 * CVE-2026-68525, 수정 11.0.25)와 tools.jackson 3.1.5(jackson-core CVE-2026-89407 / CVE-2026-89425,
 * jackson-databind CVE-2026-68497 / CVE-2026-91776 / CVE-2026-91777, 수정 3.1.7)에 걸려 {@code
 * build.gradle.kts}가 {@code extra["tomcat.version"]}·{@code extra["jackson-bom.version"]}(Boot 4에서는
 * Jackson 3 BOM)로 카탈로그 값을 소비한다. 이 덮어쓰기는 Boot의 다음 패치/마이너 릴리스(4.1.x 후속 또는 4.2.x)가 같은 버전 이상을 흡수하면
 * 불필요해지는데, 그 시점을 누구도 능동적으로 알려주지 않으므로 방치되면 영구 고착된다. 이 테스트는 각 기한 이후 {@code ./gradlew test}/{@code
 * check}를 실제로 실패시켜 CI에서 검토를 강제한다.
 *
 * <p><b>만료 시 조치</b>: Spring Boot 최신판이 tomcat ≥ 11.0.25(또는 jackson ≥ 3.1.7)를 관리하는지 확인한다. 흡수했다면 해당
 * 카탈로그 항목, {@code build.gradle.kts}의 덮어쓰기 줄, 이 클래스의 해당 메서드를 함께 삭제한다(둘 다 흡수하면 클래스째 삭제). 아직 미흡수라면 해당
 * 기한을 새 날짜로 갱신한다.
 */
class DependencyVersionOverrideExpiryTest {

    private static final LocalDate TOMCAT_REVIEW_BY = LocalDate.of(2026, 12, 9);
    private static final LocalDate JACKSON_REVIEW_BY = LocalDate.of(2026, 12, 30);

    @Test
    @DisplayName("tomcat 버전 덮어쓰기 검토 기한이 지나지 않아야 한다")
    void tomcatVersionOverrideMustBeReviewedBeforeExpiry() {
        assertThat(LocalDate.now())
                .as(
                        "gradle/libs.versions.toml의 tomcat 버전 덮어쓰기(CVE-2026-65182 / CVE-2026-65905 / "
                                + "CVE-2026-68525 대응) 검토 기한이 지났다. Spring Boot 4.1.x 후속 패치 또는 4.2.x가 "
                                + "tomcat-embed-core ≥ 11.0.25를 관리하는지 확인하고, 흡수했다면 카탈로그의 tomcat 항목, "
                                + "build.gradle.kts의 덮어쓰기 줄, 이 메서드를 함께 삭제한다. 아직 미흡수이면 "
                                + "TOMCAT_REVIEW_BY를 갱신한다.")
                .isBefore(TOMCAT_REVIEW_BY);
    }

    @Test
    @DisplayName("jackson 3 BOM 버전 덮어쓰기 검토 기한이 지나지 않아야 한다")
    void jacksonBomVersionOverrideMustBeReviewedBeforeExpiry() {
        assertThat(LocalDate.now())
                .as(
                        "gradle/libs.versions.toml의 jackson3Bom 버전 덮어쓰기(jackson-core CVE-2026-89407 / "
                                + "CVE-2026-89425, jackson-databind CVE-2026-68497 / CVE-2026-91776 / CVE-2026-91777 "
                                + "대응) 검토 기한이 지났다. Spring Boot 4.1.x 후속 패치 또는 4.2.x가 tools.jackson ≥ 3.1.7을 "
                                + "관리하는지 확인하고, 흡수했다면 카탈로그의 jackson3Bom 항목, build.gradle.kts의 덮어쓰기 "
                                + "줄, 이 메서드를 함께 삭제한다. 아직 미흡수이면 JACKSON_REVIEW_BY를 갱신한다.")
                .isBefore(JACKSON_REVIEW_BY);
    }
}
