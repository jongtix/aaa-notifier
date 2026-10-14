package com.aaa.notifier;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.ClassUtils;

/**
 * smoke 프로파일 자동구성 제외 계약 (SPEC-INFRA-BOOT4-001 REQ-BOOT4-012, AC-BOOT4-012 (b)).
 *
 * <p>Spring Boot는 {@code spring.autoconfigure.exclude}에 적힌 클래스가 클래스패스에 없으면 오류 없이 무시한다. Boot 메이저
 * 전환으로 자동구성 클래스의 패키지·이름이 바뀌면 제외만 조용히 사라지므로, ① 제외 항목 각각이 실제로 등록된 자동구성 후보이고 ② smoke 컨텍스트에 제외 대상이 만드는
 * 빈이 없음을 단언한다.
 */
@SpringBootTest
@ActiveProfiles({"test", "smoke"})
@DisplayName("smoke 프로파일 — 자동구성 제외 항목이 실제 자동구성을 가리키고 DB·Redis 빈이 없다")
class SmokeProfileAutoConfigExclusionContractTest {

    /** smoke 프로파일 제외 항목 수 기준선(Redis 자동구성 2 + DataSource 자동구성 1). */
    private static final int EXCLUSION_COUNT = 3;

    @Autowired private ApplicationContext context;

    @Autowired private Environment environment;

    private List<String> exclusions() {
        return Binder.get(environment)
                .bind("spring.autoconfigure.exclude", Bindable.listOf(String.class))
                .orElse(List.of());
    }

    @Test
    @DisplayName("제외 항목이 모두 클래스패스에 있는 등록된 자동구성 후보다")
    void exclusionsAreRegisteredAutoConfigurations() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Set<String> candidates = new HashSet<>();
        ImportCandidates.load(AutoConfiguration.class, classLoader).forEach(candidates::add);

        List<String> exclusions = exclusions();

        assertThat(exclusions).hasSize(EXCLUSION_COUNT);
        assertThat(exclusions).allMatch(name -> ClassUtils.isPresent(name, classLoader));
        assertThat(candidates).containsAll(exclusions);
    }

    @Test
    @DisplayName("smoke 컨텍스트에 DataSource·RedisConnectionFactory 빈이 없다")
    void excludedBeansAreAbsent() {
        assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
        assertThat(context.getBeanNamesForType(RedisConnectionFactory.class)).isEmpty();
    }
}
