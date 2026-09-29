package com.aaa.notifier.filter;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 현재 유효한 {@link ReferenceSnapshot}을 쥐는 단일 참조.
 *
 * <p>쓰기는 장전 cron 스레드, 읽기는 틱 소비 스레드다. 스냅샷 자체가 불변이므로 원자적 참조 교체만으로 충분하다.
 */
public class ReferenceDataHolder {

    private final AtomicReference<ReferenceSnapshot> snapshot =
            new AtomicReference<>(ReferenceSnapshot.empty());

    /** 현재 스냅샷. 최초 적재 전에는 빈 스냅샷이다. */
    public ReferenceSnapshot current() {
        return snapshot.get();
    }

    /**
     * 스냅샷을 교체한다.
     *
     * @param next 새 스냅샷
     */
    public void replace(ReferenceSnapshot next) {
        snapshot.set(Objects.requireNonNull(next, "snapshot must not be null"));
    }
}
