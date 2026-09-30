package com.aaa.notifier.telegram;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 텔레그램 패키지 테스트 공용 픽스처 — 설정 값 묶음과 자동 구성 평가 러너. */
final class TelegramTestSupport {

    private TelegramTestSupport() {}

    /** application.yml의 {@code notifier.telegram.*} 수치 설정을 그대로 옮긴 값 + 실발송 모드 켜짐. */
    static String[] enabledProperties() {
        return new String[] {
            "notifier.telegram.enabled=true",
            "notifier.telegram.base-url=http://localhost:1",
            "notifier.telegram.min-send-interval=1s",
            "notifier.telegram.connect-timeout=3s",
            "notifier.telegram.read-timeout=10s",
            "notifier.telegram.dispatch-buffer-capacity=100",
            "notifier.telegram.alert-stream-max-len=500",
            "notifier.telegram.retry.transient-retries=2",
            "notifier.telegram.retry.backoff-initial=1s",
            "notifier.telegram.retry.backoff-multiplier=2",
            "notifier.telegram.rate-limit.default-wait=5s",
            "notifier.telegram.rate-limit.max-cumulative-wait=30s",
            "notifier.telegram.rate-limit.max-resends=3",
            "notifier.telegram.safe-mode.failure-threshold=3",
            "notifier.telegram.safe-mode.probe-cron=0 * * * * *",
            "notifier.telegram.queue.retention=24h",
            "notifier.telegram.queue.summary-list-limit=10"
        };
    }

    /** 자동 구성 평가용 러너. 협력 빈은 호출 쪽이 {@code withBean}으로 채운다. */
    static ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner();
    }
}
