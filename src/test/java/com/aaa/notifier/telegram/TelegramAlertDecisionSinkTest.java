package com.aaa.notifier.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.notifier.filter.FilterMetrics;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("TelegramAlertDecisionSink — 결정 수신 분리·억제 결정 비기록 (REQ-002·004)")
class TelegramAlertDecisionSinkTest {

    private DispatcherHarness h;
    private TelegramAlertDecisionSink sink;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        h = new DispatcherHarness();
        sink = new TelegramAlertDecisionSink(h.dispatcher, FilterMetrics.create(h.registry));
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        h.dispatcher.stop();
    }

    @Test
    @DisplayName("AC-04 — 텔레그램 응답이 막혀 있어도 결정 수신 호출은 즉시 반환한다 (< 100ms)")
    void onDecision_returnsWithoutWaitingForTelegram() {
        h.api.onSend(
                n -> {
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        h.dispatcher.start();

        for (int i = 0; i < 3; i++) {
            long started = System.nanoTime();
            sink.onDecision(TelegramAlerts.candidate("t" + i));
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofMillis(100));
        }
    }

    @Test
    @DisplayName("AC-02 — 억제 결정은 발송·행 없이 FILTER-001과 같은 형식의 WARN 로그와 결정 계측만 남긴다")
    void suppressed_logsOnly(CapturedOutput output) {
        sink.onDecision(TelegramAlerts.suppressed("trace-suppressed"));
        h.drain();

        assertThat(h.api.sentTexts).isEmpty();
        assertThat(h.log.rows).isEmpty();
        assertThat(h.dryRun.decisions).isEmpty();
        assertThat(output.getAll())
                .contains("[filter-decision] 억제 trace_id=trace-suppressed")
                .contains("reason=COOLDOWN_ACTIVE");
        assertThat(h.counter("notifier.filter.decision", "outcome", "suppressed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("AC-11 ⑥ — 운영자 ON 상태에서도 억제 결정은 행을 남기지 않는다")
    void suppressedUnderOperatorOn_noRow() {
        h.safeMode.set("ON");

        sink.onDecision(TelegramAlerts.suppressed("trace-suppressed"));
        h.drain();

        assertThat(h.dryRun.decisions).isEmpty();
        assertThat(h.log.rows).isEmpty();
    }

    @Test
    @DisplayName("발송 후보는 디스패처로 넘기고 발송 결과가 확정될 때 결정 계측을 한 번만 올린다")
    void candidate_isDispatchedAndCountedOnce() {
        sink.onDecision(TelegramAlerts.candidate("trace-c"));
        h.drain();

        assertThat(h.api.sentTexts).hasSize(1);
        assertThat(h.counter("notifier.filter.decision", "outcome", "candidate")).isEqualTo(1.0);
    }
}
