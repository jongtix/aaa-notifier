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
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
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

    /**
     * 유지 시간 설정 6키의 외부화·기동 검증 (SPEC-NOTIFIER-FILTER-002 REQ-012/016, AC-11·AC-17).
     *
     * <p>기동 검증은 {@code application.yml}의 {@code notifier.filter.*} 값을 그대로 넣은 컨텍스트에서 한 키만 바꾸거나 빼서
     * 확인한다 — 유지 시간을 다른 값으로 재정의하는 테스트는 이 클래스 하나뿐이다(acceptance.md 품질 게이트).
     */
    @Nested
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("유지 시간 설정 (FILTER-002 AC-11/AC-17)")
    class DwellSettings {

        private static final String DWELL = "notifier.filter.dwell.";

        private static final List<String> DWELL_KEYS =
                List.of(
                        "direct",
                        "hold-exit",
                        "weakening",
                        "hold-entry",
                        "strength-up",
                        "strong-entry");

        /** {@code application.yml}의 필터 설정에서 {@code overrides}를 덮어쓰고 {@code removed} 키를 뺀 속성 목록. */
        private static String[] filterProperties(Map<String, String> overrides, String removed) {
            Properties yaml = applicationYaml();
            // stringPropertyNames()는 정수 값(lookback-days 등) 키를 빼므로 keySet + getProperty(문자열 변환)로 읽는다
            Map<String, String> merged =
                    yaml.keySet().stream()
                            .map(String::valueOf)
                            .filter(name -> name.startsWith("notifier.filter."))
                            .collect(
                                    Collectors.toMap(
                                            name -> name,
                                            yaml::getProperty,
                                            (first, second) -> first,
                                            TreeMap::new));
            merged.putAll(overrides);
            if (removed != null) {
                merged.remove(removed); // TreeMap은 null 키를 받지 않는다
            }
            return merged.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .toArray(String[]::new);
        }

        private static ApplicationContextRunner runner(String... properties) {
            return new ApplicationContextRunner()
                    .withUserConfiguration(FilterPropertiesBinding.class)
                    .withPropertyValues(properties);
        }

        private static String failureMessage(Throwable failure) {
            return NestedExceptionUtils.getMostSpecificCause(failure).getMessage();
        }

        @Test
        @DisplayName("AC-11 — 유지 시간 6키가 외부화되어 있고 운영 값으로 기동한다")
        void dwellKeys_areExternalizedAndBind() {
            assertThat(applicationYaml())
                    .containsKeys(DWELL_KEYS.stream().map(key -> DWELL + key).toArray());
            runner(filterProperties(Map.of(), null))
                    .run(
                            context ->
                                    assertThat(context.getBean(FilterProperties.class).dwell())
                                            .isEqualTo(FilterPipelines.DWELL));
        }

        @Test
        @DisplayName("AC-11 ③ — 테스트 조립 헬퍼의 유지 시간 6개가 application.yml 값과 같다 (N5)")
        void helperDwell_matchesApplicationYaml() {
            Properties yaml = applicationYaml();
            FilterProperties.Dwell helper = FilterPipelines.DWELL;

            assertThat(
                            DWELL_KEYS.stream()
                                    .map(
                                            key ->
                                                    DurationStyle.detectAndParse(
                                                            yaml.getProperty(DWELL + key)))
                                    .toList())
                    .containsExactly(
                            helper.direct(),
                            helper.holdExit(),
                            helper.weakening(),
                            helper.holdEntry(),
                            helper.strengthUp(),
                            helper.strongEntry());
        }

        static Stream<Arguments> invalidValues() {
            return DWELL_KEYS.stream()
                    .flatMap(
                            key ->
                                    Stream.of(
                                            Arguments.of(key, null),
                                            Arguments.of(key, "0s"),
                                            Arguments.of(key, "-1m")));
        }

        @ParameterizedTest(name = "{0} = {1}")
        @MethodSource("invalidValues")
        @DisplayName("AC-11 — 누락·0·음수 유지 시간은 기동을 실패시키고 설정 이름을 밝힌다")
        void invalidDwell_failsStartupNamingKey(String key, String value) {
            String[] properties =
                    value == null
                            ? filterProperties(Map.of(), DWELL + key)
                            : filterProperties(Map.of(DWELL + key, value), null);

            runner(properties)
                    .run(
                            context -> {
                                assertThat(context).hasFailed();
                                assertThat(failureMessage(context.getStartupFailure()))
                                        .contains(DWELL + key);
                            });
        }

        static Stream<Arguments> orderViolations() {
            return Stream.of(
                    Arguments.of(
                            Map.of(DWELL + "direct", "8m"),
                            List.of(DWELL + "direct(8m)", DWELL + "hold-exit(7m)")),
                    Arguments.of(
                            Map.of(DWELL + "weakening", "11m"),
                            List.of(DWELL + "weakening(11m)", DWELL + "hold-entry(10m)")),
                    Arguments.of(
                            Map.of(DWELL + "strength-up", "16m"),
                            List.of(DWELL + "strength-up(16m)", DWELL + "strong-entry(15m)")),
                    Arguments.of(
                            Map.of(DWELL + "direct", "8m", DWELL + "strength-up", "16m"),
                            List.of(
                                    DWELL + "direct(8m)",
                                    DWELL + "hold-exit(7m)",
                                    DWELL + "strength-up(16m)",
                                    DWELL + "strong-entry(15m)")));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("orderViolations")
        @DisplayName("AC-17 — 순서 세 쌍을 어기면 기동을 거부하고 위반한 쌍의 키·값을 오류 로그와 예외에 남긴다")
        void orderViolation_failsStartupAndLogsPair(
                Map<String, String> overrides, List<String> expected, CapturedOutput output) {
            runner(filterProperties(overrides, null))
                    .run(
                            context -> {
                                assertThat(context).hasFailed();
                                assertThat(failureMessage(context.getStartupFailure()))
                                        .contains(expected);
                            });

            assertThat(output.getOut()).contains("ERROR").contains(expected);
        }

        static Stream<Arguments> allowedOrders() {
            return Stream.of(
                    Arguments.of(Map.of(DWELL + "direct", "7m")),
                    Arguments.of(Map.of(DWELL + "weakening", "10m")),
                    Arguments.of(Map.of(DWELL + "strength-up", "15m")),
                    Arguments.of(Map.of(DWELL + "hold-exit", "8m")));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("allowedOrders")
        @DisplayName("AC-17 경계 — 같은 값이거나 세 쌍 밖의 순서(HOLD 이탈 > 강도 완화)만 바뀌면 기동한다")
        void allowedOrder_starts(Map<String, String> overrides) {
            runner(filterProperties(overrides, null))
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    /** 필터 설정 바인딩만 올리는 최소 구성 — 파이프라인 빈은 Redis·DB 없이 올라가지 않으므로 바인딩 검증만 따로 한다. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FilterProperties.class)
    static class FilterPropertiesBinding {}

    /** 이 레포의 조상 디렉터리 중 {@code aaa-analyzer}를 형제로 가진 곳을 찾는다(메인 체크아웃·워크트리 모두 대응). */
    private static Optional<Path> locateAnalyzerBoundaries() {
        return Stream.iterate(Path.of("").toAbsolutePath(), path -> path != null, Path::getParent)
                .map(path -> path.resolve(ANALYZER_BOUNDARIES))
                .filter(Files::isRegularFile)
                .findFirst();
    }
}
