package com.aaa.notifier.telegram;

/**
 * safe_mode 키({@code safe_mode:notifier:telegram}) 포트 (REQ-031/032, design.md §2).
 *
 * <p>notifier가 쓰는 값은 {@code AUTO}·{@code OFF}뿐이고 두 쓰기 모두 <b>조건부</b>다 — 읽기와 쓰기 사이에 운영자가 값을 바꿨으면 운영자
 * 값이 이긴다. 불변식: notifier는 해석 상태가 {@code ON}인 값을 절대 다른 값으로 바꾸지 않는다.
 */
public interface SafeModeStore {

    /**
     * 현재 값을 읽는다. 읽기 실패는 {@code OFF} 간주 + {@link SafeModeReading#readFailed()}로 표시한다(plan.md §D.10).
     * 예외를 던지지 않는다.
     *
     * @return 해석 결과
     */
    SafeModeReading read();

    /**
     * 가장 최근에 읽은 상태 — Redis를 읽지 않는다. 틱 소비 스레드의 버퍼 초과 경로가 쓴다([F-7], design.md §4.2).
     *
     * @return 마지막 관측 상태
     */
    SafeModeState lastObserved();

    /**
     * T1 자동 진입 — 키가 없거나 {@code OFF}로 해석될 때에 한해 {@code AUTO}를 조건부로 기록한다.
     *
     * @return 기록 결과
     */
    AutoEntry enterAuto();

    /**
     * T2 프로브 해제 — {@code observed}를 읽은 뒤에도 원래 값이 그대로 {@code AUTO}일 때에 한해 {@code OFF}를 조건부로 기록한다.
     *
     * @param observed 프로브가 {@code getMe} 전에 읽은 값
     * @return 해제했는지
     */
    boolean releaseAuto(SafeModeReading observed);

    /** 자동 진입 시도 결과. */
    enum AutoEntry {
        /** {@code AUTO}를 기록했다. */
        WRITTEN,
        /** 값이 {@code OFF}가 아니어서(운영자 값·이미 {@code AUTO}) 기록하지 않았다. */
        REJECTED,
        /** Redis 오류로 기록하지 못했다 — 다음 소진 때 다시 시도된다(plan.md §D.10). */
        FAILED
    }
}
