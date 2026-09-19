package com.aaa.notifier.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * DLQ 이관 테스트 (REQ-NOTIFIER-CONSUMER-021/022, AC-9/AC-11).
 *
 * <p>순서가 계약이다 — <b>DLQ XADD가 원본 XACK보다 먼저</b>여야 한다. 반대 순서면 XADD 실패 시 원본은 이미 확인응답되어 메시지가 어디에도 남지
 * 않는다.
 */
@DisplayName("DeadLetterPublisher — DLQ 이관 (REQ-021/022, AC-9/AC-11)")
class DeadLetterPublisherTest {

    private static final String ORIGINAL_ID = "1758100000000-0";

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final StreamOperations<String, String, String> streamOperations =
            mock(StreamOperations.class);

    private DeadLetterPublisher publisher;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger publisherLogger;

    @BeforeEach
    void setUp() {
        when(redisTemplate.<String, String>opsForStream()).thenReturn(streamOperations);
        publisher = new DeadLetterPublisher(redisTemplate);

        publisherLogger = (Logger) LoggerFactory.getLogger(DeadLetterPublisher.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        publisherLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        publisherLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Nested
    @DisplayName("AC-9 — 이관 페이로드 보존")
    class TransferPayload {

        @Test
        @DisplayName("① 원본 필드가 전부 보존된다")
        void preservesAllOriginalFields() {
            transfer(DeadLetterPublisher.Reason.MAX_DELIVERY_EXCEEDED);

            Map<String, String> payload = capturedPayload();

            assertThat(payload)
                    .containsEntry("symbol", "005930")
                    .containsEntry("trId", "H0STCNT0")
                    .containsEntry("data", "005930^151425")
                    .containsEntry("trace_id", KisFrameFixtures.TRACE_ID);
        }

        @Test
        @DisplayName("②③④ original_id·original_stream·reason이 부가된다")
        void addsProvenanceFields() {
            transfer(DeadLetterPublisher.Reason.MAX_DELIVERY_EXCEEDED);

            Map<String, String> payload = capturedPayload();

            assertThat(payload).containsEntry("original_id", ORIGINAL_ID);
            assertThat(payload).containsEntry("original_stream", "stream:tick:domestic");
            assertThat(payload).containsEntry("reason", "max_delivery_exceeded");
        }

        @Test
        @DisplayName("해석 불가 이관은 reason이 payload_unparseable이다")
        void unparseableReasonCode() {
            transfer(DeadLetterPublisher.Reason.PAYLOAD_UNPARSEABLE);

            assertThat(capturedPayload()).containsEntry("reason", "payload_unparseable");
        }

        @Test
        @DisplayName("DLQ 키는 stream:dlq: + 원본 스트림명이다")
        void writesToDlqKey() {
            transfer(DeadLetterPublisher.Reason.MAX_DELIVERY_EXCEEDED);

            assertThat(capturedRecord().getStream()).isEqualTo("stream:dlq:stream:tick:domestic");
        }
    }

    @Nested
    @DisplayName("AC-9 단언 ⑤ — 이관 후 원본 확인응답")
    class AcknowledgeAfterTransfer {

        @Test
        @DisplayName("원본 스트림에서 해당 ID를 확인응답한다")
        void acknowledgesOriginal() {
            transfer(DeadLetterPublisher.Reason.MAX_DELIVERY_EXCEEDED);

            verify(streamOperations).acknowledge("stream:tick:domestic", "notifier", ORIGINAL_ID);
        }

        @Test
        @DisplayName("DLQ XADD가 원본 XACK보다 먼저 호출된다 (역순이면 XADD 실패 시 메시지가 소실된다)")
        void addsToDlqBeforeAcknowledging() {
            transfer(DeadLetterPublisher.Reason.MAX_DELIVERY_EXCEEDED);

            InOrder order = inOrder(streamOperations);
            order.verify(streamOperations).add(any(MapRecord.class));
            order.verify(streamOperations).acknowledge(anyString(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("AC-11 — 알림 채널 미발송 + WARN 구조화 로그")
    class NotificationBoundary {

        @Test
        @DisplayName("② 이관 시 WARN 로그가 남고 trace_id가 포함된다")
        void logsWarnWithTraceId() {
            transfer(DeadLetterPublisher.Reason.MAX_DELIVERY_EXCEEDED);

            List<ILoggingEvent> warns =
                    logAppender.list.stream()
                            .filter(event -> event.getLevel() == Level.WARN)
                            .toList();

            assertThat(warns).hasSize(1);
            assertThat(warns.getFirst().getFormattedMessage())
                    .contains(KisFrameFixtures.TRACE_ID)
                    .contains(ORIGINAL_ID)
                    .contains("max_delivery_exceeded");
        }

        @Test
        @DisplayName("① 스트림 패키지 소스에 텔레그램·HTTP 발송 심볼이 0건이다")
        void streamPackage_referencesNoNotificationChannel() {
            List<String> violations =
                    StreamSourceScan.findOccurrences(
                            List.of("RestClient", "RestTemplate", "telegram", "sendMessage"));

            assertThat(violations)
                    .as("DLQ 이관은 구조화 로그까지만 남긴다 — 사람에게 도달하는 경로는 OBSV-001 소관 (REQ-022)")
                    .isEmpty();
        }
    }

    private void transfer(DeadLetterPublisher.Reason reason) {
        publisher.transfer(
                ConsumedStream.TICK_DOMESTIC,
                ORIGINAL_ID,
                KisFrameFixtures.tickEntry(
                        "005930", "H0STCNT0", "005930^151425", KisFrameFixtures.TRACE_ID),
                reason);
    }

    /**
     * 프로덕션이 실제로 호출하는 오버로드는 {@code add(MapRecord)}다 — {@code add(Record)}로 검증하면 호출되지 않은 것으로 나온다.
     * Mockito가 default 메서드까지 스텁하므로 {@code add(MapRecord)}의 본문(내부적으로 {@code add(Record)} 위임)이 실행되지
     * 않기 때문이다.
     */
    @SuppressWarnings("unchecked")
    private MapRecord<String, String, String> capturedRecord() {
        ArgumentCaptor<MapRecord<String, String, String>> captor =
                ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOperations).add(captor.capture());
        return captor.getValue();
    }

    private Map<String, String> capturedPayload() {
        return capturedRecord().getValue();
    }
}
