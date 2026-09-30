package com.aaa.notifier.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.notifier.telegram.TelegramFakes.LoggedRow;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 대기 큐 요약 발송 (REQ-033, AC-13, design.md §5, plan.md §D.12·§D.13).
 *
 * <p>큐 12건 중 1건은 25시간 전 적재(보존 기간 초과)다.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("대기 큐 요약 — 본문·행·폐기·재시도 (REQ-033, AC-13)")
class QueueSummaryTest {

    private DispatcherHarness h;

    @BeforeEach
    void setUp() {
        h = new DispatcherHarness();
        LocalDateTime now = DispatcherHarness.START.toLocalDateTime();
        h.queue.append(
                QueuedAlert.of(
                        TelegramAlerts.candidate("expired-0"),
                        now.minusHours(25),
                        DivertReason.TRANSIENT_EXHAUSTED));
        for (int i = 1; i <= 11; i++) {
            h.queue.append(
                    QueuedAlert.of(
                            TelegramAlerts.candidate("queued-" + i),
                            now.minusMinutes(60L - i),
                            DivertReason.TRANSIENT_EXHAUSTED));
        }
        h.dispatcher.requestSummary();
    }

    private List<LoggedRow> rows(EventType type) {
        return h.log.of(type);
    }

    @Test
    @DisplayName("AC-13 — 요약 1건: 장애 기간·미발송 11건·목록 10줄·나머지 1건은 건수로만")
    void summaryBody() {
        h.dispatcher.processPendingSummary();

        assertThat(h.api.sentTexts).hasSize(1);
        String text = h.api.sentTexts.getFirst();
        assertThat(text).contains("장애 기간: 2026-09-29 09:01~2026-09-29 10:00").contains("미발송 11건");
        assertThat(text.lines().filter(line -> line.contains("005930"))).hasSize(10);
        assertThat(text).contains("외 1건");
    }

    @Test
    @DisplayName(
            "AC-13 ① — SUMMARY_SENT·QUEUE_SUMMARY 행: 새 trace_id(≤64자, 원본과 다름), message_text = 보낸 본문")
    void summaryRow() {
        h.dispatcher.processPendingSummary();

        LoggedRow row = rows(EventType.SUMMARY_SENT).getFirst();
        assertThat(row.notificationType()).isEqualTo("QUEUE_SUMMARY");
        assertThat(row.traceId())
                .isNotBlank()
                .hasSizeLessThanOrEqualTo(64)
                .doesNotStartWith("queued-")
                .doesNotStartWith("expired-");
        assertThat(row.text()).isEqualTo(h.api.sentTexts.getFirst());
    }

    @Test
    @DisplayName("AC-13 ① — 요약 trace_id를 담은 구조화 로그 1건에 포함 원본 11개·폐기 원본 1개 trace_id가 있다")
    void summaryCorrelationLog(CapturedOutput output) {
        h.dispatcher.processPendingSummary();

        String summaryTraceId = rows(EventType.SUMMARY_SENT).getFirst().traceId();
        String line =
                output.getAll()
                        .lines()
                        .filter(l -> l.contains("[telegram-summary]") && l.contains(summaryTraceId))
                        .findFirst()
                        .orElseThrow();
        assertThat(line)
                .contains("result=SENT")
                .contains("queued-1")
                .contains("queued-11")
                .contains("expired-0");
    }

    @Test
    @DisplayName("AC-13 ② ③ — 성공하면 대기 큐가 비고 보존 기간 초과 폐기 계측이 +1")
    void success_clearsQueueAndCountsExpired() {
        h.dispatcher.processPendingSummary();

        assertThat(h.queue.items).isEmpty();
        assertThat(h.counter("notifier.telegram.queue.discarded", "reason", "retention_expired"))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("AC-13 ④ — 요약이 503이면 큐 12건 그대로, 행 없음, 연속 실패 카운터 불변, 재시도 목록에 초과분이 없다")
    void transientFailure_keepsQueue() {
        h.api.always(SendOutcome.transientFailure("HTTP 503"));

        h.dispatcher.processPendingSummary();

        assertThat(h.queue.items).hasSize(12);
        assertThat(h.log.rows).isEmpty();
        assertThat(h.dispatcher.consecutiveFailures()).isZero();
        assertThat(h.api.sentTexts)
                .allSatisfy(
                        text -> assertThat(text).doesNotContain("expired-0").contains("미발송 11건"));
    }

    @Test
    @DisplayName(
            "AC-13 ⑤ — 요약이 400이면 SEND_FAILED·QUEUE_SUMMARY 행, 포함 11건 폐기(요약 영구 실패 +11·보존 초과 +1), 이후 재시도 없음")
    void permanentFailure_discards() {
        h.api.always(SendOutcome.permanentFailure("HTTP 400"));

        h.dispatcher.processPendingSummary();
        h.dispatcher.requestSummary();
        h.dispatcher.processPendingSummary();

        assertThat(rows(EventType.SEND_FAILED))
                .singleElement()
                .extracting(LoggedRow::notificationType)
                .isEqualTo("QUEUE_SUMMARY");
        assertThat(h.queue.items).isEmpty();
        assertThat(
                        h.counter(
                                "notifier.telegram.queue.discarded",
                                "reason",
                                "summary_permanent_failure"))
                .isEqualTo(11.0);
        assertThat(h.counter("notifier.telegram.queue.discarded", "reason", "retention_expired"))
                .isEqualTo(1.0);
        assertThat(h.api.sentTexts).hasSize(1);
    }

    @Test
    @DisplayName("safe_mode가 켜져 있으면 요약 요청을 보류한다 — 요청·행 없음, 큐 유지")
    void safeModeOn_defersSummary() {
        h.safeMode.set("AUTO");

        h.dispatcher.processPendingSummary();

        assertThat(h.api.sentTexts).isEmpty();
        assertThat(h.log.rows).isEmpty();
        assertThat(h.queue.items).hasSize(12);
    }

    @Test
    @DisplayName("보존 기간 안 항목이 하나도 없으면 발송 없이 초과 항목만 잘라내고 폐기 계측")
    void onlyExpired_trimsWithoutSending() {
        DispatcherHarness onlyOld = new DispatcherHarness();
        onlyOld.queue.append(
                QueuedAlert.of(
                        TelegramAlerts.candidate("expired"),
                        DispatcherHarness.START.toLocalDateTime().minusHours(30),
                        DivertReason.SHUTDOWN));
        onlyOld.dispatcher.requestSummary();

        onlyOld.dispatcher.processPendingSummary();

        assertThat(onlyOld.api.sentTexts).isEmpty();
        assertThat(onlyOld.queue.items).isEmpty();
        assertThat(
                        onlyOld.counter(
                                "notifier.telegram.queue.discarded", "reason", "retention_expired"))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("요약 결과는 연속 실패 카운터를 바꾸지 않는다 — 요약 요청이 없으면 아무것도 하지 않는다")
    void noRequest_noop() {
        h.dispatcher.processPendingSummary();
        int sent = h.api.sentTexts.size();

        h.dispatcher.processPendingSummary();

        assertThat(h.api.sentTexts).hasSize(sent);
    }
}
