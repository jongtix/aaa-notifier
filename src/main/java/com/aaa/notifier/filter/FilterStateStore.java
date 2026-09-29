package com.aaa.notifier.filter;

import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 필터 상태 Redis 저장소 (design.md §2).
 *
 * <p>모든 {@code filter:*} 키는 예외 없이 {@code {종목코드}:{horizon}} 두 차원을 포함한다(REQ-013 — horizon 독립). 장중 상태
 * 키의 만료는 절대 시각({@code EXPIREAT})이 아니라 호출자가 주입 시계로 계산한 '장 마감까지 남은 시간' TTL로 건다 — 같은 순간을 뜻하면서 테스트에서
 * 시계를 고정할 수 있다.
 *
 * <p>{@code filter:hysteresis} 키는 두지 않는다(REQ-011 후단, TECHSPEC §8.2) — 이원 경계 판정이 히스테리시스 구현의 전부다.
 */
@RequiredArgsConstructor
public class FilterStateStore {

    private static final String GRADE_PREFIX = "filter:grade:";

    private final StringRedisTemplate redisTemplate;

    static String gradeKey(String symbol, String horizon) {
        return GRADE_PREFIX + symbol + ":" + horizon;
    }

    /**
     * 현재 유효 등급을 읽는다.
     *
     * @return 키가 없거나 해석 불가면 빈 값
     */
    public Optional<Grade> grade(String symbol, String horizon) {
        return Grade.parse(redisTemplate.opsForValue().get(gradeKey(symbol, horizon)));
    }

    /**
     * 유효 등급을 장 마감까지 기록한다.
     *
     * @param ttl 장 마감까지 남은 시간(양수)
     */
    public void setGrade(String symbol, String horizon, Grade grade, Duration ttl) {
        redisTemplate.opsForValue().set(gradeKey(symbol, horizon), grade.name(), ttl);
    }

    /**
     * 유효 등급이 없을 때만 초기값을 기록한다 (REQ-003 — 장중 재시작 시 기존 값 승계, 덮어쓰기 금지).
     *
     * @param ttl 장 마감까지 남은 시간(양수)
     * @return 새로 기록했으면 {@code true}, 기존 값이 있어 승계했으면 {@code false}
     */
    public boolean initGradeIfAbsent(String symbol, String horizon, Grade grade, Duration ttl) {
        return Boolean.TRUE.equals(
                redisTemplate
                        .opsForValue()
                        .setIfAbsent(gradeKey(symbol, horizon), grade.name(), ttl));
    }
}
