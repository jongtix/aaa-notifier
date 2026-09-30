package com.aaa.notifier.telegram;

/**
 * 대기 큐 {@code queue:telegram:pending} 포트 (TECHSPEC §1.3, plan.md §D.7).
 *
 * <p>앞에서 잘라내는 쪽은 디스패처(요약) 하나뿐이고 다른 쓰기는 모두 뒤에 붙인다 — 요약이 읽은 개수만큼만 잘라내면 요약 도중 뒤에 붙은 항목은
 * 보존된다(research.md §4.3 ④). Redis 오류는 {@code DataAccessException}으로 전파하며 호출 쪽이 처리한다.
 */
public interface PendingQueue {

    /** 항목을 뒤에 붙이고 키의 백스톱 TTL을 갱신한다. */
    void append(QueuedAlert alert);

    /** 전체를 앞에서부터 읽는다. */
    QueueSnapshot snapshot();

    /** 앞에서 {@code count}개를 잘라낸다. */
    void removeFirst(int count);

    /** 현재 길이. */
    long size();
}
