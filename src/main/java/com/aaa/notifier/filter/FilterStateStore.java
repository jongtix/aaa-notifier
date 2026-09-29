package com.aaa.notifier.filter;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 필터 상태 저장소 경계 (design.md §2) — 운영 구현은 {@link RedisFilterStateStore}.
 *
 * <p>장중 상태의 만료는 절대 시각({@code EXPIREAT})이 아니라 호출자가 주입 시계로 계산한 '장 마감까지 남은 시간' TTL로 건다 — 같은 순간을 뜻하면서
 * 테스트에서 시계를 고정할 수 있다.
 */
public interface FilterStateStore {

    /**
     * 현재 유효 등급을 읽는다.
     *
     * @return 키가 없거나 해석 불가면 빈 값
     */
    Optional<Grade> grade(String symbol, String horizon);

    /**
     * 유효 등급을 기록한다.
     *
     * @param ttl 장 마감까지 남은 시간(양수)
     */
    void setGrade(String symbol, String horizon, Grade grade, Duration ttl);

    /**
     * 유효 등급이 없을 때만 초기값을 기록한다 (REQ-003 — 장중 재시작 시 기존 값 승계, 덮어쓰기 금지).
     *
     * @param ttl 장 마감까지 남은 시간(양수)
     * @return 새로 기록했으면 {@code true}, 기존 값이 있어 승계했으면 {@code false}
     */
    boolean initGradeIfAbsent(String symbol, String horizon, Grade grade, Duration ttl);

    /** 당일 신호 스냅샷을 읽는다. */
    Optional<SignalSnapshot> signal(String symbol, String horizon);

    /** 당일 신호 스냅샷을 덮어쓴다(만료 없음 — 다음 신호가 덮어쓸 때까지 유지). */
    void saveSignal(String symbol, String horizon, SignalSnapshot snapshot);

    /** 확증 대기 후보를 읽는다. */
    Optional<PendingTransition> pending(String symbol, String horizon);

    /**
     * 확증 대기 후보를 기록한다(기존 후보는 교체).
     *
     * @param ttl 장 마감까지 남은 시간(양수)
     */
    void savePending(String symbol, String horizon, PendingTransition pending, Duration ttl);

    /** 확증 대기 후보를 지운다(발송 후보 확정·억제 확정 시). */
    void clearPending(String symbol, String horizon);

    /**
     * 진행 중인 쿨다운을 읽는다.
     *
     * @return 쿨다운을 건 결정의 전환 후 등급. 만료됐거나 없으면 빈 값
     */
    Optional<Grade> cooldown(String symbol, String horizon, int tier);

    /** 쿨다운을 건다 — {@code duration} 경과 후 자동 만료된다. */
    void startCooldown(String symbol, String horizon, int tier, Grade toGrade, Duration duration);

    /** 종목·horizon의 모든 Tier 쿨다운을 지운다(장 시작 리셋, REQ-034 후단). */
    void clearCooldowns(String symbol, String horizon);

    /**
     * confidence 회전 윈도에 값을 덧붙이고 최근 {@code windowSize}개만 남긴다 (REQ-051). 만료를 걸지 않는다 — 신호는 하루 1회라 장 마감
     * 만료를 걸면 추세가 영영 쌓이지 않는다(기존 설계의 TTL 버그).
     */
    void appendConfidence(String symbol, String horizon, BigDecimal confidence, int windowSize);

    /** confidence 회전 윈도 — 오래된 값 → 최신 값 순서. */
    List<BigDecimal> confidences(String symbol, String horizon);
}
