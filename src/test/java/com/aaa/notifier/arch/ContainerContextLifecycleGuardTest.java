package com.aaa.notifier.arch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaEnumConstant;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.testcontainers.junit.jupiter.Container;

/**
 * 컨테이너를 소유한 통합 테스트 컨텍스트의 수명 정렬 가드 (SPEC-NOTIFIER-CONSUMER-001 AC-3 간헐 실패의 근본 원인 재발 방지).
 *
 * <p><b>근본 원인.</b> Spring 테스트 컨텍스트는 클래스가 끝나도 캐시에 살아 남는다. 그런데 {@code @Container} 필드는 클래스가 끝나면 컨테이너를
 * 내린다. 소비 계층을 품은 컨텍스트가 컨테이너보다 오래 살면, 그 컨텍스트의 소비 워커(같은 그룹·같은 컨슈머 이름)가 죽은 호스트 포트로 재연결을 계속 시도한다. 다음
 * 클래스의 컨테이너가 Docker에게서 <b>같은 호스트 포트</b>를 다시 받으면, 죽은 컨텍스트의 워커(무동작 핸들러)가 새 Redis에 접속해 메시지를 가로채 확인응답해
 * 버린다 — 하류 핸들러는 호출되지 않는데 미확인 건수는 0이 되어, 통합 테스트가 <b>실행 순서와 포트 재사용 여부에 따라 간헐적으로</b> 실패한다(실측 재현: 포트
 * 재사용 실행에서 {@code parsed ... handler=NoOpStreamObservationHandler}가 라이브 컨테이너에서 관측됨).
 *
 * <p><b>규칙.</b> {@code @SpringBootTest}이면서 {@code @Container} 필드를 가진 클래스는 {@code @DirtiesContext}로
 * 클래스 종료 시 컨텍스트를 함께 닫아야 한다({@code AFTER_CLASS} 또는 {@code AFTER_EACH_TEST_METHOD}). 컨텍스트의 수명이 컨테이너의
 * 수명을 넘지 않게 하는 것이 목적이다.
 *
 * <p>순수 클래스파일 스캔이므로 컨테이너를 기동하지 않고 pre-push 단위 테스트 집합에 포함되어도 비용이 없다.
 */
@DisplayName("컨테이너 소유 통합 테스트의 컨텍스트 수명 정렬 가드 (@DirtiesContext)")
class ContainerContextLifecycleGuardTest {

    private static final String CONTAINER_ANNOTATION = Container.class.getName();

    @Test
    @DisplayName("GREEN — @SpringBootTest + @Container 클래스는 전부 클래스 종료 시 컨텍스트를 닫는다")
    void springContainerClasses_allCloseContextWithContainer() {
        // Arrange
        Iterable<JavaClass> scannedClasses =
                new ClassFileImporter().importPackages("com.aaa.notifier");

        List<String> violations = new ArrayList<>();
        int scannedCount = 0;

        // Act — fixture(자기 자신의 중첩 클래스)는 RED 재현용이므로 GREEN 스캔에서 제외한다.
        for (JavaClass javaClass : scannedClasses) {
            if (javaClass
                    .getName()
                    .startsWith(ContainerContextLifecycleGuardTest.class.getName())) {
                continue;
            }
            scannedCount++;
            if (ownsContainerInSpringContext(javaClass) && !closesContextWithClass(javaClass)) {
                violations.add(javaClass.getFullName());
            }
        }

        // Assert
        assertThat(scannedCount).as("스캔 결과가 비어 있으면 가드가 동작하지 않는다").isPositive();
        if (!violations.isEmpty()) {
            fail(
                    "@SpringBootTest + @Container 이면서 @DirtiesContext(AFTER_CLASS)가 없는 클래스 ("
                            + violations.size()
                            + "건):\n"
                            + String.join("\n", violations)
                            + "\n\n[해결] 클래스 레벨에 @DirtiesContext(classMode = AFTER_CLASS)를 추가할 것 —"
                            + " 컨텍스트가 컨테이너보다 오래 살면 죽은 포트를 재사용한 다음 컨테이너에 워커가 접속해"
                            + " 메시지를 가로챈다.");
        }
    }

    @Test
    @DisplayName("RED 입증 — @DirtiesContext가 없는 fixture를 규칙이 위반으로 판정한다")
    void rule_detectsMissingDirtiesContext() {
        JavaClass fixture =
                new ClassFileImporter().importClass(Fixtures.WithoutDirtiesContext.class);

        assertThat(ownsContainerInSpringContext(fixture)).isTrue();
        assertThat(closesContextWithClass(fixture)).as("컨텍스트를 닫지 않는 fixture는 위반이다").isFalse();
    }

    @Test
    @DisplayName("RED 입증 — classMode가 BEFORE_CLASS인 fixture는 컨테이너보다 컨텍스트가 오래 산다")
    void rule_detectsBeforeClassMode() {
        JavaClass fixture = new ClassFileImporter().importClass(Fixtures.BeforeClassMode.class);

        assertThat(closesContextWithClass(fixture)).isFalse();
    }

    @Test
    @DisplayName("GREEN 입증 — AFTER_CLASS 명시와 기본값(생략) fixture는 위반이 아니다")
    void rule_passesForAfterClassExplicitAndDefault() {
        JavaClass explicit = new ClassFileImporter().importClass(Fixtures.AfterClassExplicit.class);
        JavaClass byDefault = new ClassFileImporter().importClass(Fixtures.AfterClassDefault.class);

        assertThat(closesContextWithClass(explicit)).isTrue();
        assertThat(closesContextWithClass(byDefault))
                .as("classMode 생략 시 기본값은 AFTER_CLASS다")
                .isTrue();
    }

    @Test
    @DisplayName("GREEN 입증 — AFTER_EACH_TEST_METHOD fixture도 위반이 아니다")
    void rule_passesForAfterEachTestMethod() {
        JavaClass fixture = new ClassFileImporter().importClass(Fixtures.AfterEachMethod.class);

        assertThat(closesContextWithClass(fixture)).isTrue();
    }

    /** {@code @SpringBootTest}이면서 {@code @Container} 필드를 하나라도 가진 클래스인가. */
    private boolean ownsContainerInSpringContext(JavaClass javaClass) {
        if (!javaClass.isAnnotatedWith(SpringBootTest.class.getName())) {
            return false;
        }
        for (JavaField field : javaClass.getFields()) {
            if (field.isAnnotatedWith(CONTAINER_ANNOTATION)) {
                return true;
            }
        }
        return false;
    }

    /** 클래스 종료 시점에 컨텍스트를 닫는 {@code @DirtiesContext}를 가졌는가 (기본 classMode는 AFTER_CLASS). */
    private boolean closesContextWithClass(JavaClass javaClass) {
        Optional<JavaAnnotation<JavaClass>> dirties =
                javaClass.tryGetAnnotationOfType(DirtiesContext.class.getName());
        if (dirties.isEmpty()) {
            return false;
        }
        Optional<Object> classMode = dirties.get().get("classMode");
        if (classMode.isEmpty() || !(classMode.get() instanceof JavaEnumConstant mode)) {
            return false;
        }
        String name = mode.name();
        return ClassMode.AFTER_CLASS.name().equals(name)
                || ClassMode.AFTER_EACH_TEST_METHOD.name().equals(name);
    }

    /**
     * 실행되지 않는 RED/GREEN 입증용 fixture — 클래스파일 스캔 대상일 뿐이다. {@code IntegrationTagGuardTest}의 통합 태그 규칙에
     * 걸리지 않도록 태그를 부여해 둔다(테스트 메서드가 없어 실행되지는 않는다).
     */
    static final class Fixtures {

        private Fixtures() {}

        @SpringBootTest
        @Tag("integration")
        static final class WithoutDirtiesContext {
            @Container static final String C = "dummy";

            private WithoutDirtiesContext() {}
        }

        @SpringBootTest
        @Tag("integration")
        @DirtiesContext(classMode = ClassMode.BEFORE_CLASS)
        static final class BeforeClassMode {
            @Container static final String C = "dummy";

            private BeforeClassMode() {}
        }

        @SpringBootTest
        @Tag("integration")
        @DirtiesContext(classMode = ClassMode.AFTER_CLASS)
        static final class AfterClassExplicit {
            @Container static final String C = "dummy";

            private AfterClassExplicit() {}
        }

        @SpringBootTest
        @Tag("integration")
        @DirtiesContext
        static final class AfterClassDefault {
            @Container static final String C = "dummy";

            private AfterClassDefault() {}
        }

        @SpringBootTest
        @Tag("integration")
        @DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)
        static final class AfterEachMethod {
            @Container static final String C = "dummy";

            private AfterEachMethod() {}
        }
    }
}
