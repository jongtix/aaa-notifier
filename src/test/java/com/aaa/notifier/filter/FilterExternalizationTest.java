package com.aaa.notifier.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * 수치 파라미터 외부화 가드 (REQ-032/081, AC-11, AC-23).
 *
 * <p>순수 파일 스캔이라 컨테이너 없이 pre-push 단위 테스트 집합에서 돈다.
 */
@DisplayName("필터 수치 파라미터 외부화 가드 (AC-11/AC-23)")
class FilterExternalizationTest {

    /** analyzer {@code grade_boundaries.json}의 δ — plan.md §F #1과 같은 값(2026-09-29 기준). */
    private static final String ANALYZER_DELTA = "0.010017154049042788";

    private static final Path ANALYZER_BOUNDARIES =
            Path.of("aaa-analyzer", "src", "analyzer", "inference", "grade_boundaries.json");

    private static Properties applicationYaml() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        Properties properties = factory.getObject();
        return properties == null ? new Properties() : properties;
    }

    @Test
    @DisplayName("AC-11 — 전환 유형별 확증 3종·쿨다운 3종(+Tier1 반복·Tier3 완화)이 외부화 키로 존재한다")
    void confirmAndCooldown_areExternalized() {
        Properties yaml = applicationYaml();

        assertThat(yaml)
                .containsKeys(
                        "notifier.filter.confirm.direct",
                        "notifier.filter.confirm.hold-exit",
                        "notifier.filter.confirm.hold-entry",
                        "notifier.filter.cooldown.direct",
                        "notifier.filter.cooldown.hold-exit",
                        "notifier.filter.cooldown.hold-entry",
                        "notifier.filter.cooldown.tier1-repeat",
                        "notifier.filter.cooldown.tier3-weakening");
    }

    @Test
    @DisplayName("AC-23 — 11개 그룹(δ·거래량·시간대 2종·ATR·확증 3종·쿨다운 3종)과 부속 키가 전부 외부화되어 있다")
    void allElevenGroups_areExternalized() {
        Properties yaml = applicationYaml();

        assertThat(yaml)
                .containsKeys(
                        "notifier.filter.band.delta",
                        "notifier.filter.guard.volume-ratio",
                        "notifier.filter.guard.open-exclusion",
                        "notifier.filter.guard.close-exclusion",
                        "notifier.filter.guard.atr-multiplier",
                        "notifier.filter.confidence.weakening-delta",
                        "notifier.filter.confidence.low-threshold",
                        "notifier.filter.confidence.window-size");
        assertThat(yaml.getProperty("notifier.filter.band.delta")).isEqualTo(ANALYZER_DELTA);
    }

    @Test
    @DisplayName("AC-23 — 필터 소스에 잠정 수치 리터럴이 하드코딩되어 있지 않다")
    void noTentativeLiteralsInSource() {
        assertThat(
                        FilterSourceScan.findOccurrences(
                                List.of(
                                        "0.010017",
                                        "\"0.30\"",
                                        "0.30",
                                        "2.5",
                                        "0.05",
                                        "0.65",
                                        "Duration.ofMinutes(")))
                .as("수치는 application.yml에만 있어야 한다 (REQ-081)")
                .isEmpty();
    }

    @Test
    @DisplayName("FilterProperties의 @DefaultValue는 enabled 플래그 하나뿐이다 (수치 기본값을 소스에 두지 않는다)")
    void onlyEnabledFlagHasDefault() {
        assertThat(
                        FilterSourceScan.findOccurrences(
                                List.of("@DefaultValue(\"true\") boolean enabled")))
                .containsExactly("FilterProperties.java:@DefaultValue(\"true\") boolean enabled");
        assertThat(FilterSourceScan.countOccurrences("@DefaultValue")).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "AC-23 — δ가 analyzer grade_boundaries.json의 grade_margin_delta.value와 수치가 같다 (analyzer 레포가 있을 때)")
    void deltaMatchesAnalyzerBoundaries() throws IOException {
        Optional<Path> boundaries = locateAnalyzerBoundaries();
        assumeTrue(
                boundaries.isPresent(), "aaa-analyzer 체크아웃이 없는 환경(CI 단일 레포) — 배포 전 수동 대조 항목으로 남긴다");

        JsonNode root = new ObjectMapper().readTree(boundaries.get().toFile());
        BigDecimal analyzerDelta = root.path("grade_margin_delta").path("value").decimalValue();
        BigDecimal notifierDelta =
                new BigDecimal(applicationYaml().getProperty("notifier.filter.band.delta"));

        assertThat(notifierDelta).isEqualByComparingTo(analyzerDelta);
    }

    /** 이 레포의 조상 디렉터리 중 {@code aaa-analyzer}를 형제로 가진 곳을 찾는다(메인 체크아웃·워크트리 모두 대응). */
    private static Optional<Path> locateAnalyzerBoundaries() {
        return Stream.iterate(Path.of("").toAbsolutePath(), path -> path != null, Path::getParent)
                .map(path -> path.resolve(ANALYZER_BOUNDARIES))
                .filter(Files::isRegularFile)
                .findFirst();
    }
}
