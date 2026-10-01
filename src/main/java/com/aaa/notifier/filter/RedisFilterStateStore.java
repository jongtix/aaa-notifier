package com.aaa.notifier.filter;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

/** {@link FilterStateStore}의 Redis 구현 — 키 패턴은 {@link FilterKeys}. */
@RequiredArgsConstructor
public class RedisFilterStateStore implements FilterStateStore {

    private static final String SIGNAL_CLASS = "signal_class";
    private static final String SCORE = "score";
    private static final String CONFIDENCE = "confidence";
    private static final String TRADE_DATE = "trade_date";
    private static final String TRACE_ID = "trace_id";
    private static final String FROM = "from";
    private static final String TO = "to";
    private static final String LAST_REASON = "last_reason";
    private static final String SINCE = "since";
    private static final int MAX_TIER = 3;

    private final StringRedisTemplate redisTemplate;

    @Override
    public Optional<Grade> grade(String symbol, String horizon) {
        return Grade.parse(redisTemplate.opsForValue().get(FilterKeys.grade(symbol, horizon)));
    }

    @Override
    public void setGrade(String symbol, String horizon, Grade grade, Duration ttl) {
        redisTemplate.opsForValue().set(FilterKeys.grade(symbol, horizon), grade.name(), ttl);
    }

    @Override
    public boolean initGradeIfAbsent(String symbol, String horizon, Grade grade, Duration ttl) {
        return Boolean.TRUE.equals(
                redisTemplate
                        .opsForValue()
                        .setIfAbsent(FilterKeys.grade(symbol, horizon), grade.name(), ttl));
    }

    @Override
    public Optional<SignalSnapshot> signal(String symbol, String horizon) {
        Map<Object, Object> fields =
                redisTemplate.opsForHash().entries(FilterKeys.signal(symbol, horizon));
        if (fields.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                new SignalSnapshot(
                        (String) fields.get(SIGNAL_CLASS),
                        new BigDecimal((String) fields.get(SCORE)),
                        new BigDecimal((String) fields.get(CONFIDENCE)),
                        LocalDate.parse((String) fields.get(TRADE_DATE)),
                        (String) fields.get(TRACE_ID)));
    }

    @Override
    public void saveSignal(String symbol, String horizon, SignalSnapshot snapshot) {
        redisTemplate
                .opsForHash()
                .putAll(
                        FilterKeys.signal(symbol, horizon),
                        Map.of(
                                SIGNAL_CLASS, snapshot.signalClass(),
                                SCORE, snapshot.score().toPlainString(),
                                CONFIDENCE, snapshot.confidence().toPlainString(),
                                TRADE_DATE, snapshot.tradeDate().toString(),
                                TRACE_ID, snapshot.traceId() == null ? "" : snapshot.traceId()));
    }

    @Override
    public Optional<PendingTransition> pending(String symbol, String horizon) {
        Map<Object, Object> fields =
                redisTemplate.opsForHash().entries(FilterKeys.confirm(symbol, horizon));
        Optional<Grade> from = Grade.parse((String) fields.get(FROM));
        Optional<Grade> to = Grade.parse((String) fields.get(TO));
        if (from.isEmpty() || to.isEmpty()) {
            return Optional.empty();
        }
        String reason = (String) fields.get(LAST_REASON);
        String since = (String) fields.get(SINCE);
        return Optional.of(
                new PendingTransition(
                        from.get(),
                        to.get(),
                        reason == null || reason.isEmpty()
                                ? null
                                : SuppressionReason.valueOf(reason),
                        // 배포 전 형식(전환 체결 시각 없음)은 null — 파이프라인이 첫 평가 체결 시각으로 채운다(REQ-011)
                        since == null || since.isEmpty()
                                ? null
                                : Instant.ofEpochMilli(Long.parseLong(since))));
    }

    @Override
    public void savePending(
            String symbol, String horizon, PendingTransition pending, Duration ttl) {
        String key = FilterKeys.confirm(symbol, horizon);
        redisTemplate
                .opsForHash()
                .putAll(
                        key,
                        Map.of(
                                FROM, pending.from().name(),
                                TO, pending.to().name(),
                                LAST_REASON,
                                        pending.lastReason() == null
                                                ? ""
                                                : pending.lastReason().name()));
        if (pending.since() != null) {
            redisTemplate
                    .opsForHash()
                    .put(key, SINCE, Long.toString(pending.since().toEpochMilli()));
        }
        redisTemplate.expire(key, ttl);
    }

    @Override
    public void clearPending(String symbol, String horizon) {
        redisTemplate.delete(FilterKeys.confirm(symbol, horizon));
    }

    @Override
    public Optional<Grade> cooldown(String symbol, String horizon, int tier) {
        return Grade.parse(
                redisTemplate.opsForValue().get(FilterKeys.cooldown(symbol, horizon, tier)));
    }

    @Override
    public void startCooldown(
            String symbol, String horizon, int tier, Grade toGrade, Duration duration) {
        redisTemplate
                .opsForValue()
                .set(FilterKeys.cooldown(symbol, horizon, tier), toGrade.name(), duration);
    }

    @Override
    public void clearCooldowns(String symbol, String horizon) {
        redisTemplate.delete(
                IntStream.rangeClosed(1, MAX_TIER)
                        .mapToObj(tier -> FilterKeys.cooldown(symbol, horizon, tier))
                        .toList());
    }

    @Override
    public void appendConfidence(
            String symbol, String horizon, BigDecimal confidence, int windowSize) {
        String key = FilterKeys.confidence(symbol, horizon);
        redisTemplate.opsForList().rightPush(key, confidence.toPlainString());
        redisTemplate.opsForList().trim(key, -windowSize, -1);
    }

    @Override
    public List<BigDecimal> confidences(String symbol, String horizon) {
        List<String> values =
                redisTemplate.opsForList().range(FilterKeys.confidence(symbol, horizon), 0, -1);
        return values == null ? List.of() : values.stream().map(BigDecimal::new).toList();
    }
}
