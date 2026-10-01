package com.aaa.notifier.filter;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 파이프라인 단위 테스트용 인메모리 상태 저장소 — Redis 없이 판정 로직만 검증한다.
 *
 * <p>TTL은 기록만 하고 스스로 만료시키지 않는다(단위 테스트는 시계를 고정하므로 만료 경과가 없다). 장 마감 만료는 {@link #expireIntraday}로
 * 모사한다(SPEC-NOTIFIER-FILTER-002 AC-09 (c)). Redis 구현의 실제 키·만료는 통합 테스트가 검증한다.
 */
class InMemoryFilterStateStore implements FilterStateStore {

    final Map<String, Grade> grades = new ConcurrentHashMap<>();
    final Map<String, Duration> gradeTtls = new ConcurrentHashMap<>();
    final Map<String, SignalSnapshot> signals = new ConcurrentHashMap<>();
    final Map<String, PendingTransition> pendings = new ConcurrentHashMap<>();

    /** 후보 키에 마지막으로 넘겨진 TTL — 키는 {@code 종목코드:horizon}. */
    final Map<String, Duration> pendingTtls = new ConcurrentHashMap<>();

    final Map<String, Set<String>> recorded = new ConcurrentHashMap<>();

    /** 방출 이력 키에 마지막으로 넘겨진 TTL — 키는 {@code 종목코드:horizon}. */
    final Map<String, Duration> recordedTtls = new ConcurrentHashMap<>();

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
        pendingTtls.put(key(symbol, horizon), ttl);
    }

    @Override
    public void clearPending(String symbol, String horizon) {
        pendings.remove(key(symbol, horizon));
    }

    @Override
    public boolean markRecorded(String symbol, String horizon, String member, Duration ttl) {
        recordedTtls.put(key(symbol, horizon), ttl);
        return recorded.computeIfAbsent(
                        key(symbol, horizon), unused -> ConcurrentHashMap.newKeySet())
                .add(member);
    }

    @Override
    public void clearRecorded(String symbol, String horizon, Collection<String> members) {
        Set<String> set = recorded.get(key(symbol, horizon));
        if (set != null) {
            set.removeAll(members);
        }
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

    /** 대기 억제 방출 이력 멤버(없으면 빈 집합). */
    Set<String> recordedOf(String symbol, String horizon) {
        return recorded.getOrDefault(key(symbol, horizon), Set.of());
    }

    /** 장 마감 만료 모사 — 마감 TTL이 걸린 장중 키(유효 등급·후보·방출 이력)를 지운다. 다일 수명 키(신호·confidence)는 남는다. */
    void expireIntraday(String symbol, String horizon) {
        grades.remove(key(symbol, horizon));
        gradeTtls.remove(key(symbol, horizon));
        pendings.remove(key(symbol, horizon));
        pendingTtls.remove(key(symbol, horizon));
        recorded.remove(key(symbol, horizon));
        recordedTtls.remove(key(symbol, horizon));
    }
}
