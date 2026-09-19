package com.aaa.notifier.stream;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** 무동작 기본 핸들러 대신 {@link RecordingStreamHandler}를 하류 포트로 주입하는 통합 테스트 전용 설정. */
@TestConfiguration
class RecordingHandlerConfiguration {

    @Bean
    RecordingStreamHandler recordingStreamHandler() {
        return new RecordingStreamHandler();
    }
}
