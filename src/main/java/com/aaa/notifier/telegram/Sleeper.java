package com.aaa.notifier.telegram;

import java.time.Duration;

/**
 * 대기 추상화 — 429 대기·백오프·발송 간격이 {@code Thread.sleep}에 직접 묶이지 않게 한다. 테스트는 시계만 움직이는 구현으로 바꿔 대기 시간을 결정적으로
 * 검증한다.
 */
@FunctionalInterface
public interface Sleeper {

    /** 운영 구현 — 현재 스레드를 재운다. */
    Sleeper THREAD = Thread::sleep;

    /**
     * @param duration 대기 시간 (0 이하면 즉시 반환해도 된다)
     * @throws InterruptedException 정상 종료 중 인터럽트
     */
    void sleep(Duration duration) throws InterruptedException;
}
