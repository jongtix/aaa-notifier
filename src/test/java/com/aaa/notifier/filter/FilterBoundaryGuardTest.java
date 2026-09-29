package com.aaa.notifier.filter;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 필터 패키지 정적 경계 가드 (REQ-002, REQ-011 후단 — AC-1 ②, AC-3 ③).
 *
 * <p>순수 클래스파일·소스 스캔이므로 컨테이너 없이 pre-push 단위 테스트 집합에서 돈다.
 */
@DisplayName("필터 패키지 정적 경계 가드")
class FilterBoundaryGuardTest {

    @Test
    @DisplayName("AC-1 ② — 틱 경로(파이프라인·판정·상태 저장소)는 JdbcTemplate/DataSource에 의존하지 않는다")
    void tickPath_doesNotTouchDatabase() {
        noClasses()
                .that()
                .haveSimpleNameNotEndingWith("AutoConfiguration")
                .and()
                .doNotHaveSimpleName("ReferenceDataLoader")
                .and()
                .doNotHaveSimpleName("JdbcDryRunAlertDecisionSink")
                .should()
                .dependOnClassesThat()
                .areAssignableTo(JdbcTemplate.class)
                .orShould()
                .dependOnClassesThat()
                .areAssignableTo(DataSource.class)
                .because("틱 처리 경로의 DB 동기 조회는 금지다 (REQ-002, ADR-027 풀 고갈 재현 조건)")
                .check(
                        new ClassFileImporter()
                                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                                .importPackages("com.aaa.notifier.filter"));
    }

    @Test
    @DisplayName("AC-3 ③ — filter:hysteresis 키·별도 히스테리시스 필터가 없다")
    void noHysteresisKey() {
        assertThat(FilterSourceScan.findOccurrences(List.of("filter:hysteresis", "hysteresis:")))
                .as("이원 경계 판정이 히스테리시스 구현의 전부다 (REQ-011 후단, TECHSPEC §8.2)")
                .isEmpty();
    }

    @Test
    @DisplayName("AC-21 — 텔레그램 발송·SENT류 INSERT·stream:alert 발행 심볼이 없다 (DRYRUN INSERT만 허용)")
    void noDownstreamSendingSymbols() {
        assertThat(
                        FilterSourceScan.findOccurrences(
                                List.of(
                                        "RestClient",
                                        "RestTemplate",
                                        "sendMessage",
                                        "stream:alert",
                                        "'SENT'",
                                        "'SEND_FAILED'",
                                        "'QUEUED'",
                                        "'SUMMARY_SENT'")))
                .as("발송·SENT류 기록·stream:alert 발행은 TELEGRAM-001 소관이다 (REQ-063)")
                .isEmpty();
        assertThat(FilterSourceScan.findOccurrences(List.of("'DRYRUN'")))
                .as("DRYRUN INSERT는 결정 싱크 기본 구현 한 곳에만 있다")
                .containsExactly("JdbcDryRunAlertDecisionSink.java:'DRYRUN'");
    }
}
