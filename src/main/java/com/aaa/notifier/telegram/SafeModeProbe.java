package com.aaa.notifier.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * safe_mode 프로브 주기 작업 (REQ-032, design.md §6, plan.md §D.9) — cron 1분(ADR-008).
 *
 * <ul>
 *   <li>{@code ON}(미인식 값 포함) — 아무것도 하지 않는다. 운영자가 켠 것은 운영자만 끈다.
 *   <li>{@code AUTO} — {@code getMe} 프로브가 성공하면 <b>읽은 값이 여전히 {@code AUTO}일 때만</b> {@code OFF}로
 *       바꾼다(T2). 성공하고 대기 큐가 있으면 요약을 요청한다.
 *   <li>{@code OFF} — 대기 큐가 있으면 요약을 요청한다(운영자 해제 반영 + 잔존 큐 배출).
 * </ul>
 *
 * <p>요약 발송 자체는 디스패처가 한다(단일 실행 지점). 읽은 값은 디스패처의 버퍼 초과 경로가 쓰는 캐시도 갱신한다.
 */
@Slf4j
@RequiredArgsConstructor
public class SafeModeProbe {

    private final SafeModeStore safeMode;
    private final TelegramApi api;
    private final PendingQueue queue;
    private final TelegramDispatcher dispatcher;
    private final TelegramMetrics metrics;

    /** 1분 주기 프로브. */
    @Scheduled(cron = "${notifier.telegram.safe-mode.probe-cron}", zone = "Asia/Seoul")
    public void probe() {
        SafeModeReading reading = safeMode.read();
        if (reading.readFailed()) {
            return;
        }
        long depth = queueDepth();
        switch (reading.state()) {
            case ON -> {
                // 운영자 kill switch — 프로브도 해제도 하지 않는다
            }
            case AUTO -> {
                if (api.probe() && safeMode.releaseAuto(reading)) {
                    log.info(
                            "[telegram-safe-mode] getMe 성공 — 자동 safe_mode 해제(AUTO→OFF) queue_depth={}",
                            depth);
                    requestSummaryIfQueued(depth);
                }
            }
            case OFF -> requestSummaryIfQueued(depth);
        }
    }

    private void requestSummaryIfQueued(long depth) {
        if (depth > 0) {
            dispatcher.requestSummary();
        }
    }

    private long queueDepth() {
        try {
            long depth = queue.size();
            metrics.queueDepth(depth);
            return depth;
        } catch (DataAccessException e) {
            log.warn("[telegram-safe-mode] 대기 큐 길이를 읽지 못했다 error={}", e.getMessage());
            return 0;
        }
    }
}
