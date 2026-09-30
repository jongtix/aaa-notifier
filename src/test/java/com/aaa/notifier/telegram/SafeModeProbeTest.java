package com.aaa.notifier.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

@DisplayName("SafeModeProbe — 자동분만 프로브로 해제, 운영자분은 운영자만 (REQ-032, AC-12)")
class SafeModeProbeTest {

    private DispatcherHarness h;
    private SafeModeProbe probe;

    @BeforeEach
    void setUp() {
        h = new DispatcherHarness();
        probe = new SafeModeProbe(h.safeMode, h.api, h.queue, h.dispatcher, h.metrics);
    }

    @Test
    @DisplayName("AUTO + getMe 성공 → OFF로 해제")
    void auto_probeSuccess_releases() {
        h.safeMode.set("AUTO");

        probe.probe();

        assertThat(h.safeMode.raw()).isEqualTo("OFF");
        assertThat(h.api.probeCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("운영자 ON이면 getMe를 보내지 않고 키를 그대로 둔다")
    void operatorOn_noProbe() {
        h.safeMode.set("ON");

        probe.probe();

        assertThat(h.safeMode.raw()).isEqualTo("ON");
        assertThat(h.api.probeCalls()).isZero();
    }

    @Test
    @DisplayName("② getMe가 실패하면 AUTO를 유지한다")
    void auto_probeFailure_keeps() {
        h.safeMode.set("AUTO");
        h.api.probe(false, () -> {});

        probe.probe();

        assertThat(h.safeMode.raw()).isEqualTo("AUTO");
    }

    @Test
    @DisplayName("④ R-B — getMe 왕복 중 운영자가 ON을 켜면 프로브 성공 뒤에도 ON 그대로")
    void operatorOnDuringProbe_keepsOn() {
        h.safeMode.set("AUTO");
        h.api.probe(true, () -> h.safeMode.set("ON"));

        probe.probe();

        assertThat(h.safeMode.raw()).isEqualTo("ON");
    }

    @Test
    @DisplayName("① 운영자가 OFF로 바꾸면 다음 주기 1회에 해제가 반영된다 — 대기 큐가 있으면 요약 요청")
    void operatorOff_requestsSummaryWhenQueueNotEmpty() {
        h.queue.append(
                QueuedAlert.of(
                        TelegramAlerts.candidate("q"),
                        DispatcherHarness.START.toLocalDateTime(),
                        DivertReason.SAFE_MODE));
        h.safeMode.set("OFF");

        probe.probe();

        assertThat(h.dispatcher.summaryRequested()).isTrue();
    }

    @Test
    @DisplayName("AUTO 해제에 성공하고 대기 큐가 있으면 요약을 요청한다")
    void releaseWithQueue_requestsSummary() {
        h.queue.append(
                QueuedAlert.of(
                        TelegramAlerts.candidate("q"),
                        DispatcherHarness.START.toLocalDateTime(),
                        DivertReason.SAFE_MODE));
        h.safeMode.set("AUTO");

        probe.probe();

        assertThat(h.dispatcher.summaryRequested()).isTrue();
    }

    @Test
    @DisplayName("대기 큐가 비어 있으면 요약을 요청하지 않고, 큐 길이 게이지를 갱신한다")
    void emptyQueue_noSummary() {
        probe.probe();

        assertThat(h.dispatcher.summaryRequested()).isFalse();
        assertThat(h.registry.get("notifier.telegram.queue.depth").gauge().value()).isZero();
    }

    @Test
    @DisplayName("③ 프로브 작업은 cron 스케줄로만 선언돼 있다 (fixedDelay 없음, ADR-008)")
    void probe_isCronScheduled() throws NoSuchMethodException {
        Method method = SafeModeProbe.class.getMethod("probe");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("${notifier.telegram.safe-mode.probe-cron}");
        assertThat(scheduled.fixedDelay()).isNegative();
        assertThat(scheduled.fixedDelayString()).isEmpty();
    }
}
