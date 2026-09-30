package com.aaa.notifier.telegram;

import java.util.List;

/**
 * 대기 큐를 앞에서부터 읽은 결과.
 *
 * @param alerts 해석한 항목 (FIFO 순서)
 * @param rawCount 읽은 원소 수 — 해석 불가 항목 포함. 요약 후 앞에서 잘라낼 개수다
 */
public record QueueSnapshot(List<QueuedAlert> alerts, int rawCount) {

    public QueueSnapshot {
        alerts = List.copyOf(alerts);
    }

    /** 해석할 수 없어 목록에서 빠진 항목 수. */
    public int unreadableCount() {
        return rawCount - alerts.size();
    }
}
