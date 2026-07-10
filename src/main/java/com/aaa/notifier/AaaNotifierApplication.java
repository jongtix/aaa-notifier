package com.aaa.notifier;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * aaa-notifier 상주 프로세스 진입점.
 *
 * <p>단일 Spring Boot 애플리케이션으로 동작한다(REQ-NOTIFIER-FOUNDATION-008). Virtual Threads는 {@code
 * application.yml}의 {@code spring.threads.virtual.enabled}로 활성화한다(REQ-NOTIFIER-FOUNDATION-011).
 *
 * <p>{@code @EnableScheduling}은 스케줄 잡 추가 시 cron 전용 규칙(ADR-008)을 인지시키기 위한 것이다. 본 골격에는 스케줄 잡이 없으며,
 * {@code @Scheduled}에 {@code fixedDelay}를 사용하면 PMD 가드({@code NoFixedDelayScheduled})가 빌드를 실패시킨다.
 */
@SpringBootApplication
@EnableScheduling
public class AaaNotifierApplication {

    public static void main(String[] args) {
        // JVM 전역 시간대를 KST로 고정 (build.gradle.kts의 -Duser.timezone과 이중 보장, 프로덕션 java -jar 환경 담당,
        // ADR-009)
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        SpringApplication.run(AaaNotifierApplication.class, args);
    }
}
