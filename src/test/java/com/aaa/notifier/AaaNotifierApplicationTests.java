package com.aaa.notifier;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles({"test", "smoke"})
@DisplayName("애플리케이션 컨텍스트 로드 + Virtual Threads 활성 (REQ-008/011)")
class AaaNotifierApplicationTests {

    @Autowired private ApplicationContext context;

    @Autowired private Environment environment;

    @Test
    @DisplayName("스프링 컨텍스트가 정상 기동한다")
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    @DisplayName("Virtual Threads가 활성화되어 있다 (spring.threads.virtual.enabled=true)")
    void virtualThreadsEnabled() {
        assertThat(environment.getProperty("spring.threads.virtual.enabled", Boolean.class))
                .isTrue();
    }
}
