package com.aaa.notifier.telegram;

import java.util.Locale;

/**
 * safe_mode 해석 상태 (REQ-NOTIFIER-TELEGRAM-031 값 해석, plan.md §D.5, design.md §2.1).
 *
 * <p>Redis 키 {@code safe_mode:notifier:telegram}의 원래 값을 앞뒤 공백 무시·대소문자 무시로 {@code ON}(운영자 출처)·{@code
 * AUTO}(자동 출처)·{@code OFF}와 비교한다. 키가 없으면 {@code OFF}다.
 *
 * <p><b>세 값 어디에도 해당하지 않는 값은 운영자 출처 {@code ON}으로 간주한다</b> — 운영자가 무언가를 써 넣었다는 것은 멈추려는 의도의 증거다. {@code
 * OFF}로 읽으면 kill switch가 오타 하나로 무력화되고, {@code AUTO}로 읽으면 프로브가 운영자 모르게 풀어 버린다. Redis를 <b>읽지 못한</b>
 * 경우의 {@code OFF} 간주(plan.md §D.10)와 방향이 반대인 것은 의도된 비대칭이다.
 */
public enum SafeModeState {
    /** 꺼짐 — 발송한다. notifier가 프로브 해제로 쓰는 값. */
    OFF,
    /** 자동 출처 — 연속 실패 차단기. 발송 후보는 대기 큐로, 프로브가 풀 수 있다. notifier가 쓰는 값. */
    AUTO,
    /** 운영자 출처 kill switch — 발송 후보는 DRYRUN 행만. notifier는 이 값을 쓰지도 풀지도 않는다. */
    ON;

    /**
     * 원래 값을 해석한다.
     *
     * @param raw Redis에서 읽은 원래 값 (키 없음 = {@code null})
     * @return 해석 상태 — 미인식 값은 {@link #ON}
     */
    public static SafeModeState interpret(String raw) {
        if (raw == null) {
            return OFF;
        }
        String normalized = raw.strip().toUpperCase(Locale.ROOT);
        for (SafeModeState state : values()) {
            if (state.name().equals(normalized)) {
                return state;
            }
        }
        return ON;
    }

    /**
     * 원래 값이 세 값 중 하나(또는 키 없음)인지. {@code false}면 호출 쪽이 원래 값을 담은 WARN 로그를 남긴다.
     *
     * @param raw Redis에서 읽은 원래 값
     * @return 인식 가능한 값인지
     */
    public static boolean isRecognized(String raw) {
        if (raw == null) {
            return true;
        }
        String normalized = raw.strip().toUpperCase(Locale.ROOT);
        for (SafeModeState state : values()) {
            if (state.name().equals(normalized)) {
                return true;
            }
        }
        return false;
    }
}
