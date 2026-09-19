package com.aaa.notifier.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 소비 계층 통합 테스트 (REQ-NOTIFIER-CONSUMER-001/002/003, AC-1/AC-2/AC-3, AC-12 종단, AC-10 종단).
 *
 * <p>실 Redis에서 그룹 생성·재기동 멱등성·스트림 간 격리·종단 배치 전달·DLQ 이관을 확인한다. 단위 테스트는 mock으로 호출 순서를 고정하지만, "그룹이 실제로
 * 생기는가"와 "XADD한 것이 하류까지 오는가"는 실물로만 확인된다.
 *
 * <p>{@code @Container} 필드를 보유하므로 클래스 레벨 {@code @Tag("integration")}가 필수다
 * (REQ-NOTIFIER-FOUNDATION-031, {@code IntegrationTagGuardTest}가 빌드 타임 강제).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
@DisplayName("소비 계층 통합 테스트 (실 Redis)")
class StreamConsumerIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private RecordingHandler handler;
    @Autowired private StreamConsumerRunner runner;

    @BeforeEach
    void resetRecordings() {
        handler.clear();
    }

    @Test
    @DisplayName("AC-1 ① — 4스트림 각각에 그룹 `notifier`가 정확히 1개 존재한다")
    void allFourStreams_haveExactlyOneNotifierGroup() {
        for (ConsumedStream stream : ConsumedStream.values()) {
            StreamInfo.XInfoGroups groups = redisTemplate.opsForStream().groups(stream.getKey());

            assertThat(groups.size()).as("스트림 %s의 그룹 수", stream.getKey()).isEqualTo(1);
            assertThat(groups.stream().findFirst().orElseThrow().groupName())
                    .isEqualTo(ConsumedStream.CONSUMER_GROUP);
        }
    }

    @Test
    @DisplayName("AC-1 ②③ — 컨슈머 이름이 규칙을 따르고 `:`를 포함하지 않는다")
    void consumerNames_areRegisteredWithoutColon() {
        for (ConsumedStream stream : ConsumedStream.values()) {
            await().atMost(WAIT)
                    .until(
                            () ->
                                    redisTemplate
                                                    .opsForStream()
                                                    .consumers(
                                                            stream.getKey(),
                                                            ConsumedStream.CONSUMER_GROUP)
                                                    .size()
                                            >= 1);

            assertThat(
                            redisTemplate
                                    .opsForStream()
                                    .consumers(stream.getKey(), ConsumedStream.CONSUMER_GROUP)
                                    .stream()
                                    .map(StreamInfo.XInfoConsumer::consumerName))
                    .as("스트림 %s의 컨슈머 이름", stream.getKey())
                    .containsExactly(stream.getConsumerName())
                    .allSatisfy(name -> assertThat(name).doesNotContain(":"));
        }
    }

    @Test
    @DisplayName("AC-2 ① — 그룹이 이미 있는 상태로 재기동해도 BUSYGROUP이 예외로 전파되지 않는다")
    void restartWithExistingGroup_isIdempotent() {
        // Arrange — 컨텍스트 기동으로 그룹은 이미 존재한다.
        runner.stop();

        // Act — 같은 생성 경로를 한 번 더 태운다. BUSYGROUP을 무시하지 않으면 여기서 터진다.
        runner.start();

        // Assert
        assertThat(runner.isRunning()).isTrue();
        for (ConsumedStream stream : ConsumedStream.values()) {
            assertThat(redisTemplate.opsForStream().groups(stream.getKey()).size()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("AC-12 종단 — 국내 배치 프레임 1엔트리가 체결 관측치 7건으로 하류에 도달한다")
    void domesticBatchEntry_reachesHandlerAsSevenObservations() {
        String data =
                KisFrameFixtures.batch(
                        KisFrameFixtures.DOMESTIC_TRADE_RECORD, 2, 12, 313_000L, 825L, 7);
        publish(
                ConsumedStream.TICK_DOMESTIC,
                KisFrameFixtures.tickEntry("005930", "H0STCNT0", data, KisFrameFixtures.TRACE_ID));

        await().atMost(WAIT).until(() -> handler.trades().size() >= 7);

        assertThat(handler.trades()).hasSize(7);
        assertThat(handler.trades())
                .extracting(t -> t.price().toPlainString())
                .containsExactly(
                        "313000", "313001", "313002", "313003", "313004", "313005", "313006");
    }

    @Test
    @DisplayName("AC-14 상관 불변식 종단 — 해외 틱의 정규 심볼이 해외 신호의 symbol과 문자열 동등하다")
    void overseasTickSymbol_matchesOverseasSignalSymbol() {
        publish(
                ConsumedStream.TICK_OVERSEAS,
                KisFrameFixtures.tickEntry(
                        "DNASAAPL",
                        "HDFSCNT0",
                        KisFrameFixtures.OVERSEAS_TRADE_RECORD,
                        KisFrameFixtures.TRACE_ID));
        publish(ConsumedStream.SIGNAL_OVERSEAS, KisFrameFixtures.signalEntry());

        await().atMost(WAIT)
                .until(() -> !handler.trades().isEmpty() && !handler.signals().isEmpty());

        assertThat(handler.trades().getFirst().symbol())
                .isEqualTo(handler.signals().getFirst().symbol());
    }

    @Test
    @DisplayName("AC-15 종단 — 신호 7필드가 하류에 도달하고 horizon이 무변환 보존된다")
    void signalEntry_reachesHandlerWithHorizonPreserved() {
        publish(ConsumedStream.SIGNAL_DOMESTIC, KisFrameFixtures.signalEntry());

        await().atMost(WAIT).until(() -> !handler.signals().isEmpty());

        assertThat(handler.signals().getFirst().horizon()).isEqualTo("D20");
    }

    @Test
    @DisplayName("AC-10 종단 — 미지의 trId 엔트리가 DLQ로 이관되고 원본 미확인 건수가 0이 된다")
    void unparseableEntry_movesToDlqAndLeavesNoPending() {
        publish(
                ConsumedStream.TICK_DOMESTIC,
                KisFrameFixtures.tickEntry(
                        "005930", "H0STZZZ0", "a^b^c", KisFrameFixtures.TRACE_ID));

        await().atMost(WAIT).until(() -> dlqSize(ConsumedStream.TICK_DOMESTIC) >= 1);

        List<MapRecord<String, String, String>> dlq =
                redisTemplate
                        .<String, String>opsForStream()
                        .range(ConsumedStream.TICK_DOMESTIC.dlqKey(), Range.unbounded());

        assertThat(dlq.getFirst().getValue())
                .containsEntry("original_stream", "stream:tick:domestic")
                .containsEntry("reason", "payload_unparseable");
        await().atMost(WAIT).until(() -> pendingCount(ConsumedStream.TICK_DOMESTIC) == 0);
    }

    @Test
    @DisplayName("AC-3 — 한 스트림의 하류 실패가 나머지 스트림의 소비를 막지 않는다")
    void failingStream_doesNotBlockOtherStreams() {
        handler.failSignals(true);
        try {
            publish(ConsumedStream.SIGNAL_DOMESTIC, KisFrameFixtures.signalEntry());
            publish(
                    ConsumedStream.TICK_DOMESTIC,
                    KisFrameFixtures.tickEntry(
                            "005930",
                            "H0STCNT0",
                            KisFrameFixtures.DOMESTIC_TRADE_RECORD,
                            KisFrameFixtures.TRACE_ID));

            // 정상 스트림은 소비되어 미확인 0건
            await().atMost(WAIT).until(() -> pendingCount(ConsumedStream.TICK_DOMESTIC) == 0);
            // 실패 스트림은 미확인 상태로 남는다.
            // 스트림마다 폴링 주기가 독립이라 신호 워커가 아직 읽기 전일 수 있다 — 읽힌 뒤의 상태를 본다.
            await().atMost(WAIT).until(() -> pendingCount(ConsumedStream.SIGNAL_DOMESTIC) > 0);
            assertThat(pendingCount(ConsumedStream.SIGNAL_DOMESTIC)).isPositive();
        } finally {
            handler.failSignals(false);
        }
    }

    private void publish(ConsumedStream stream, Map<String, String> fields) {
        redisTemplate.opsForStream().add(MapRecord.create(stream.getKey(), fields));
    }

    private long pendingCount(ConsumedStream stream) {
        PendingMessagesSummary summary =
                redisTemplate
                        .opsForStream()
                        .pending(stream.getKey(), ConsumedStream.CONSUMER_GROUP);
        return summary == null ? 0L : summary.getTotalPendingMessages();
    }

    private long dlqSize(ConsumedStream stream) {
        Long size = redisTemplate.opsForStream().size(stream.dlqKey());
        return size == null ? 0L : size;
    }

    /** 하류 도달을 기록하고, 필요 시 특정 관측치 종류에만 실패를 주입하는 테스트 핸들러. */
    static class RecordingHandler implements StreamObservationHandler {

        private final List<TradeTickObservation> tradeLog = new CopyOnWriteArrayList<>();
        private final List<SignalObservation> signalLog = new CopyOnWriteArrayList<>();
        private final AtomicBoolean signalFailure = new AtomicBoolean();

        @Override
        public void onTradeTick(TradeTickObservation observation) {
            tradeLog.add(observation);
        }

        @Override
        public void onQuote(QuoteObservation observation) {
            // 이 테스트는 호가 경로를 검증하지 않는다 — 호가 파싱 계약은 단위 테스트가 고정한다.
        }

        @Override
        public void onSignal(SignalObservation observation) {
            if (signalFailure.get()) {
                throw new IllegalStateException("주입된 하류 실패");
            }
            signalLog.add(observation);
        }

        List<TradeTickObservation> trades() {
            return new ArrayList<>(tradeLog);
        }

        List<SignalObservation> signals() {
            return new ArrayList<>(signalLog);
        }

        void failSignals(boolean fail) {
            signalFailure.set(fail);
        }

        void clear() {
            tradeLog.clear();
            signalLog.clear();
        }
    }

    @TestConfiguration
    static class HandlerConfiguration {

        @Bean
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }
    }
}
