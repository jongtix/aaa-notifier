package com.aaa.notifier.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.notifier.telegram.SendOutcome.Kind;
import com.aaa.notifier.telegram.TelegramMetrics.Channel;
import com.aaa.notifier.telegram.TelegramMetrics.DiscardReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TelegramMetrics — 발송·대기 큐·safe_mode 계측 (REQ-052, AC-16)")
class TelegramMetricsTest {

    private SimpleMeterRegistry registry;
    private TelegramMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = TelegramMetrics.create(registry);
    }

    private double counter(String name, String... tags) {
        Counter found = registry.find(name).tags(tags).counter();
        return found == null ? -1 : found.count();
    }

    private double gauge(String name, String... tags) {
        return registry.get(name).tags(tags).gauge().value();
    }

    @Test
    @DisplayName("AC-16 — 생성 즉시 대기 큐 길이·safe_mode 게이지가 값 0으로 존재한다 (첫 발송 전)")
    void gauges_registeredAtCreation() {
        assertThat(gauge("notifier.telegram.queue.depth")).isZero();
        assertThat(gauge("notifier.telegram.safe.mode", "source", "auto")).isZero();
        assertThat(gauge("notifier.telegram.safe.mode", "source", "operator")).isZero();
    }

    @Test
    @DisplayName("AC-16 ① — 게이지 이름에 _total 접미사가 없다")
    void gaugeNames_haveNoTotalSuffix() {
        assertThat(registry.getMeters())
                .filteredOn(meter -> meter.getId().getType() == Meter.Type.GAUGE)
                .isNotEmpty()
                .allSatisfy(meter -> assertThat(meter.getId().getName()).doesNotEndWith("total"));
    }

    @Test
    @DisplayName("AC-16 ③ ④ — 지연 분포·기록 실패·발행 실패 시리즈가 생성 즉시 존재한다")
    void failureSeries_existAtCreation() {
        assertThat(registry.find("notifier.telegram.send.latency").timer()).isNotNull();
        assertThat(counter("notifier.telegram.record.failure")).isZero();
        assertThat(counter("notifier.telegram.alert.publish.failure")).isZero();
        assertThat(counter("notifier.telegram.queue.write.failure")).isZero();
    }

    @Test
    @DisplayName("AC-16 ④ — 폐기 카운터는 사유(보존 기간 초과 / 요약 영구 실패)별 시리즈로 생성 즉시 존재한다")
    void discardSeries_existPerReasonAtCreation() {
        assertThat(counter("notifier.telegram.queue.discarded", "reason", "retention_expired"))
                .isZero();
        assertThat(
                        counter(
                                "notifier.telegram.queue.discarded",
                                "reason",
                                "summary_permanent_failure"))
                .isZero();
    }

    @Test
    @DisplayName("AC-16 ② — 결과 카운터는 4분류와 알림/요약을 구분해 센다")
    void sendResults_areCountedByKindAndChannel() {
        metrics.sendResult(Channel.ALERT, Kind.SUCCESS);
        metrics.sendResult(Channel.ALERT, Kind.RATE_LIMITED);
        metrics.sendResult(Channel.ALERT, Kind.TRANSIENT);
        metrics.sendResult(Channel.SUMMARY, Kind.PERMANENT);

        assertThat(counter("notifier.telegram.send", "channel", "alert", "result", "success"))
                .isEqualTo(1.0);
        assertThat(counter("notifier.telegram.send", "channel", "alert", "result", "rate_limited"))
                .isEqualTo(1.0);
        assertThat(counter("notifier.telegram.send", "channel", "alert", "result", "transient"))
                .isEqualTo(1.0);
        assertThat(counter("notifier.telegram.send", "channel", "summary", "result", "permanent"))
                .isEqualTo(1.0);
        assertThat(counter("notifier.telegram.send", "channel", "summary", "result", "success"))
                .isZero();
    }

    @Test
    @DisplayName("safe_mode 게이지는 출처별로 0/1을 나타낸다")
    void safeModeGauge_bySource() {
        metrics.safeMode(SafeModeState.AUTO);
        assertThat(gauge("notifier.telegram.safe.mode", "source", "auto")).isEqualTo(1.0);
        assertThat(gauge("notifier.telegram.safe.mode", "source", "operator")).isZero();

        metrics.safeMode(SafeModeState.ON);
        assertThat(gauge("notifier.telegram.safe.mode", "source", "auto")).isZero();
        assertThat(gauge("notifier.telegram.safe.mode", "source", "operator")).isEqualTo(1.0);

        metrics.safeMode(SafeModeState.OFF);
        assertThat(gauge("notifier.telegram.safe.mode", "source", "operator")).isZero();
    }

    @Test
    @DisplayName("대기 큐 길이·폐기·실패 계측이 반영된다")
    void queueAndFailureCounters() {
        metrics.queueDepth(12);
        metrics.discarded(DiscardReason.RETENTION_EXPIRED, 1);
        metrics.discarded(DiscardReason.SUMMARY_PERMANENT_FAILURE, 11);
        metrics.recordFailure();
        metrics.publishFailure();
        metrics.queueWriteFailure();
        metrics.latency(Duration.ofSeconds(2));

        assertThat(gauge("notifier.telegram.queue.depth")).isEqualTo(12.0);
        assertThat(counter("notifier.telegram.queue.discarded", "reason", "retention_expired"))
                .isEqualTo(1.0);
        assertThat(
                        counter(
                                "notifier.telegram.queue.discarded",
                                "reason",
                                "summary_permanent_failure"))
                .isEqualTo(11.0);
        assertThat(counter("notifier.telegram.record.failure")).isEqualTo(1.0);
        assertThat(registry.get("notifier.telegram.send.latency").timer().count()).isEqualTo(1);
    }
}
