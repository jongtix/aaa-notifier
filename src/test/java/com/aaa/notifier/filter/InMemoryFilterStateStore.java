package com.aaa.notifier.filter;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
    final Map<String, PendingTransition> pendings = new ConcurrentHashMap<>();
    final Map<String, Grade> cooldowns = new ConcurrentHashMap<>();
    final Map<String, List<BigDecimal>> confidenceWindows = new ConcurrentHashMap<>();

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

    @Override
    public Optional<PendingTransition> pending(String symbol, String horizon) {
        return Optional.ofNullable(pendings.get(key(symbol, horizon)));
    }

    @Override
    public void savePending(
            String symbol, String horizon, PendingTransition pending, Duration ttl) {
        pendings.put(key(symbol, horizon), pending);
    }

    @Override
    public void clearPending(String symbol, String horizon) {
        pendings.remove(key(symbol, horizon));
    }

    @Override
    public Optional<Grade> cooldown(String symbol, String horizon, int tier) {
        return Optional.ofNullable(cooldowns.get(key(symbol, horizon) + ":" + tier));
    }

    @Override
    public void startCooldown(
            String symbol, String horizon, int tier, Grade toGrade, Duration duration) {
        cooldowns.put(key(symbol, horizon) + ":" + tier, toGrade);
    }

    @Override
    public void clearCooldowns(String symbol, String horizon) {
        cooldowns
                .keySet()
                .removeIf(cooldownKey -> cooldownKey.startsWith(key(symbol, horizon) + ":"));
    }

    @Override
    public void appendConfidence(
            String symbol, String horizon, BigDecimal confidence, int windowSize) {
        List<BigDecimal> window =
                new ArrayList<>(confidenceWindows.getOrDefault(key(symbol, horizon), List.of()));
        window.add(confidence);
        confidenceWindows.put(
                key(symbol, horizon),
                List.copyOf(
                        window.subList(Math.max(0, window.size() - windowSize), window.size())));
    }

    @Override
    public List<BigDecimal> confidences(String symbol, String horizon) {
        return confidenceWindows.getOrDefault(key(symbol, horizon), List.of());
    }

    Grade gradeOf(String symbol, String horizon) {
        return grades.get(key(symbol, horizon));
    }
}
