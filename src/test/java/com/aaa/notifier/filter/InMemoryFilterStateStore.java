package com.aaa.notifier.filter;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 파이프라인 단위 테스트용 인메모리 상태 저장소 — Redis 없이 판정 로직만 검증한다.
 *
 * <p>TTL은 기록만 하고 만료시키지 않는다(단위 테스트는 시계를 고정하므로 만료 경과가 없다). Redis 구현의 실제 키·만료는 통합 테스트가 검증한다.
 */
class InMemoryFilterStateStore implements FilterStateStore {

    final Map<String, Grade> grades = new ConcurrentHashMap<>();
    final Map<String, Duration> gradeTtls = new ConcurrentHashMap<>();
    final Map<String, SignalSnapshot> signals = new ConcurrentHashMap<>();

    private static String key(String symbol, String horizon) {
        return symbol + ":" + horizon;
    }

    @Override
    public Optional<Grade> grade(String symbol, String horizon) {
        return Optional.ofNullable(grades.get(key(symbol, horizon)));
    }

    @Override
    public void setGrade(String symbol, String horizon, Grade grade, Duration ttl) {
        grades.put(key(symbol, horizon), grade);
        gradeTtls.put(key(symbol, horizon), ttl);
    }

    @Override
    public boolean initGradeIfAbsent(String symbol, String horizon, Grade grade, Duration ttl) {
        if (grades.containsKey(key(symbol, horizon))) {
            return false;
        }
        setGrade(symbol, horizon, grade, ttl);
        return true;
    }

    @Override
    public Optional<SignalSnapshot> signal(String symbol, String horizon) {
        return Optional.ofNullable(signals.get(key(symbol, horizon)));
    }

    @Override
    public void saveSignal(String symbol, String horizon, SignalSnapshot snapshot) {
        signals.put(key(symbol, horizon), snapshot);
    }

    Grade gradeOf(String symbol, String horizon) {
        return grades.get(key(symbol, horizon));
    }
}
