package com.aaa.notifier.common.logging;

import org.slf4j.MDC;

/**
 * MDC 래퍼 유틸리티 클래스.
 *
 * <p>MDC 접근을 이 클래스로 단일화하여, 향후 민감 값 마스킹 훅(Bot Token 등)을 한 곳에 주입할 수 있게 한다. 현재 골격 단계에서는 마스킹 대상이
 * 없으므로(마스킹 프레임워크의 실제 적용은 TELEGRAM-001 소관) 값을 그대로 MDC에 저장하는 얇은 래퍼로 시작한다.
 *
 * <p>모든 메서드는 stateless이며 thread-safe하다({@link MDC}에 위임).
 */
public final class SafeMdc {

    private SafeMdc() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * 값을 MDC에 저장한다.
     *
     * @param key MDC 키 이름 (null 불허)
     * @param value 저장할 값 (null 허용)
     * @throws IllegalArgumentException key가 null인 경우
     */
    public static void put(String key, String value) {
        if (key == null) {
            throw new IllegalArgumentException("MDC key must not be null");
        }
        MDC.put(key, value);
    }

    /**
     * MDC에서 해당 키를 제거한다.
     *
     * @param key 제거할 MDC 키 이름 (null 불허)
     * @throws IllegalArgumentException key가 null인 경우
     */
    public static void remove(String key) {
        if (key == null) {
            throw new IllegalArgumentException("MDC key must not be null");
        }
        MDC.remove(key);
    }

    /**
     * MDC의 모든 키를 제거한다.
     *
     * <p>다른 컴포넌트가 설정한 키까지 모두 삭제하므로, 요청/태스크의 최외곽 경계에서만 호출해야 한다. 개별 키 정리에는 {@link #remove}를 사용한다.
     */
    public static void clear() {
        MDC.clear();
    }

    /**
     * MDC에서 해당 키의 값을 반환한다.
     *
     * @param key 조회할 MDC 키 이름
     * @return MDC에 저장된 값, 없으면 null
     */
    public static String get(String key) {
        return MDC.get(key);
    }
}
