package com.aaa.notifier.filter;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 필터 파이프라인 설정 (REQ-NOTIFIER-FILTER-081 — 수치 파라미터 전부 외부화, plan.md §F).
 *
 * <p>수치 파라미터에는 {@code @DefaultValue}를 두지 않는다 — 소스에 잠정값이 박히면 {@code application.yml}과 두 벌이 되어 어느 쪽이
 * 실제 값인지 흐려진다. 값은 {@code application.yml} 한 곳에만 있고, 누락되면 기동 시점에 실패한다.
 *
 * @param enabled 파이프라인 활성화. {@code false}면 소비 계층의 무동작 핸들러가 남는다
 * @param referenceLoad 장전 적재 설정
 * @param band 밴드 판정 설정
 * @param guard 가드 필터 설정
 * @param confirm 전환 유형별 확증 횟수
 * @param dwell 유지 시간 유형별 유지 시간 (SPEC-NOTIFIER-FILTER-002)
 * @param cooldown 전환 유형별·Tier별 쿨다운
 * @param confidence confidence 방향성·저확신 설정
 */
@ConfigurationProperties(prefix = "notifier.filter")
public record FilterProperties(
        @DefaultValue("true") boolean enabled,
        ReferenceLoad referenceLoad,
        Band band,
        Guard guard,
        Confirm confirm,
        Dwell dwell,
        Cooldown cooldown,
        Confidence confidence) {

    public FilterProperties {
        Objects.requireNonNull(referenceLoad, "notifier.filter.reference-load는 필수다");
        Objects.requireNonNull(band, "notifier.filter.band는 필수다");
        Objects.requireNonNull(guard, "notifier.filter.guard는 필수다");
        Objects.requireNonNull(confirm, "notifier.filter.confirm은 필수다");
        Objects.requireNonNull(dwell, "notifier.filter.dwell은 필수다");
        Objects.requireNonNull(cooldown, "notifier.filter.cooldown은 필수다");
        Objects.requireNonNull(confidence, "notifier.filter.confidence는 필수다");
    }

    /**
     * @param domesticCron 국내 장전 적재 cron (KST)
     * @param overseasCron 해외 장전 적재 cron (ET)
     * @param lookbackDays {@code daily_ohlcv}·밴드 조회 창(달력일). 양수
     */
    public record ReferenceLoad(String domesticCron, String overseasCron, int lookbackDays) {

        public ReferenceLoad {
            if (lookbackDays <= 0) {
                throw new IllegalArgumentException(
                        "notifier.filter.reference-load.lookback-days는 양수여야 한다 (현재 값: "
                                + lookbackDays
                                + ")");
            }
        }
    }

    /**
     * @param delta [1] δ — 밴드 이원 경계 마진(score 축). analyzer {@code grade_boundaries.json}과 같은 값이어야
     *     한다. notifier는 이미 δ가 반영된 밴드를 소비하므로 계산에 쓰지 않는 대조용 미러 값이다
     */
    public record Band(BigDecimal delta) {

        public Band {
            Objects.requireNonNull(delta, "notifier.filter.band.delta는 필수다");
        }
    }

    /**
     * 가드 3종 (REQ-021/022). 평가 순서는 거래량 → 시간대 → 변동성이다(REQ-024).
     *
     * @param volumeRatio [2] 거래량 가드 — 같은 시각까지의 20일 평균 누적 거래량 대비 비율 임계
     * @param openExclusion [3] 시간대 가드 — 개장 직후 제외 구간
     * @param closeExclusion [4] 시간대 가드 — 마감 직전 제외 구간
     * @param atrMultiplier [5] ATR 가드 — 당일 변동폭이 ATR(14)의 이 배수를 넘으면 과열
     */
    public record Guard(
            BigDecimal volumeRatio,
            Duration openExclusion,
            Duration closeExclusion,
            BigDecimal atrMultiplier) {

        public Guard {
            Objects.requireNonNull(volumeRatio, "notifier.filter.guard.volume-ratio는 필수다");
            Objects.requireNonNull(openExclusion, "notifier.filter.guard.open-exclusion은 필수다");
            Objects.requireNonNull(closeExclusion, "notifier.filter.guard.close-exclusion은 필수다");
            Objects.requireNonNull(atrMultiplier, "notifier.filter.guard.atr-multiplier는 필수다");
        }
    }

    /**
     * 전환 유형별 확증 횟수 — 직접 &lt; HOLD 이탈 &lt; HOLD 진입 순으로 엄격(REQ-032). ※칸도 동시 성립 유형의 값을 쓴다(REQ-042).
     *
     * @param direct [6] 직접 전환
     * @param holdExit [7] HOLD 이탈
     * @param holdEntry [8] HOLD 진입
     */
    public record Confirm(int direct, int holdExit, int holdEntry) {

        public Confirm {
            if (direct < 1 || holdExit < 1 || holdEntry < 1) {
                throw new IllegalArgumentException(
                        "notifier.filter.confirm.* 는 1 이상이어야 한다 (현재 값: direct="
                                + direct
                                + ", hold-exit="
                                + holdExit
                                + ", hold-entry="
                                + holdEntry
                                + ")");
            }
        }
    }

    /**
     * 유지 시간 유형별 유지 시간 (SPEC-NOTIFIER-FILTER-002 REQ-008/012/016, design.md §3). 전환 후보는 새 유효 등급이 전환
     * 체결 시각부터 이 시간 이상 끊김 없이 유지돼야 확증을 통과한다.
     *
     * <p>누락·0 이하는 키 이름을 밝힌 예외로 기동을 실패시킨다(REQ-012). 순서 세 쌍(직접 ≤ HOLD 이탈, 강도 완화 ≤ HOLD 진입, 순수 강도 강화 ≤
     * HOLD 진입·STRONG)을 어기면 위반한 쌍을 모두 오류 로그에 남기고 기동을 실패시킨다(REQ-016) — 컨텍스트 기동 실패는 예외만 남기므로 로그는 여기서
     * 직접 쓴다.
     *
     * @param direct ① 직접 전환(STRONG 도착 ※칸 포함)
     * @param holdExit ② HOLD 이탈
     * @param weakening ③ 강도 완화(Tier 3)
     * @param holdEntry ④ HOLD 진입·STRONG 아닌 도착
     * @param strengthUp ⑤ 순수 강도 강화(Tier 1)
     * @param strongEntry ⑥ HOLD 진입·STRONG 도착(Tier 1※)
     */
    @Slf4j
    public record Dwell(
            Duration direct,
            Duration holdExit,
            Duration weakening,
            Duration holdEntry,
            Duration strengthUp,
            Duration strongEntry) {

        private static final String PREFIX = "notifier.filter.dwell.";

        public Dwell {
            requirePositive("direct", direct);
            requirePositive("hold-exit", holdExit);
            requirePositive("weakening", weakening);
            requirePositive("hold-entry", holdEntry);
            requirePositive("strength-up", strengthUp);
            requirePositive("strong-entry", strongEntry);
            List<String> violations = new ArrayList<>();
            checkOrder(violations, "direct", direct, "hold-exit", holdExit);
            checkOrder(violations, "weakening", weakening, "hold-entry", holdEntry);
            checkOrder(violations, "strength-up", strengthUp, "strong-entry", strongEntry);
            if (!violations.isEmpty()) {
                violations.forEach(violation -> log.error("유지 시간 순서 위반 — {}", violation));
                throw new IllegalArgumentException(
                        "notifier.filter.dwell 순서 위반 — " + String.join(", ", violations));
            }
        }

        private static void requirePositive(String key, Duration value) {
            if (value == null) {
                throw new IllegalArgumentException(PREFIX + key + "는 필수다");
            }
            if (value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException(
                        PREFIX + key + "는 양수여야 한다 (현재 값: " + format(value) + ")");
            }
        }

        /** {@code shorter ≤ longer}(같음 허용)가 아니면 위반 문구를 더한다. */
        private static void checkOrder(
                List<String> violations,
                String shorterKey,
                Duration shorter,
                String longerKey,
                Duration longer) {
            if (shorter.compareTo(longer) > 0) {
                violations.add(
                        PREFIX
                                + shorterKey
                                + "("
                                + format(shorter)
                                + ") ≤ "
                                + PREFIX
                                + longerKey
                                + "("
                                + format(longer)
                                + ") 이어야 한다");
            }
        }

        /** 설정 파일 표기와 같은 단위로 보여 준다 — 분 단위면 {@code 8m}, 초 단위면 {@code 90s}. */
        private static String format(Duration value) {
            if (value.getNano() == 0 && value.getSeconds() % 60 == 0) {
                return value.toMinutes() + "m";
            }
            return value.getNano() == 0 ? value.getSeconds() + "s" : value.toString();
        }
    }

    /**
     * 쿨다운 — 직접(없음) &lt; HOLD 이탈 &lt; HOLD 진입 순으로 길어진다(REQ-032). 0은 "쿨다운 없음"이다.
     *
     * @param direct [9] 직접 전환
     * @param holdExit [10] HOLD 이탈
     * @param holdEntry [11] HOLD 진입
     * @param tier1Repeat Tier-1 동일방향 반복(순수 강도 강화 칸, REQ-035)
     * @param tier3Weakening Tier 3 강도 완화 칸(design.md §4 M5 확인 사항 — HOLD 이탈과 같은 '짧음' 등급의 잠정값)
     */
    public record Cooldown(
            Duration direct,
            Duration holdExit,
            Duration holdEntry,
            Duration tier1Repeat,
            Duration tier3Weakening) {

        public Cooldown {
            for (Duration value :
                    new Duration[] {direct, holdExit, holdEntry, tier1Repeat, tier3Weakening}) {
                Objects.requireNonNull(value, "notifier.filter.cooldown.* 는 필수다");
                if (value.isNegative()) {
                    throw new IllegalArgumentException(
                            "notifier.filter.cooldown.* 는 음수일 수 없다 (현재 값: " + value + ")");
                }
            }
        }
    }

    /**
     * confidence 방향성 검사와 저확신 표시 (REQ-051~053).
     *
     * @param weakeningDelta 회전 윈도가 연속 하락하면서 처음 − 마지막 하락 폭이 이 값 이상이면 약화 추세
     * @param lowThreshold 이 값 미만 confidence는 저확신 표시 플래그(발송 차단 아님, 구 Tier-1 게이트 임계의 재용도)
     * @param windowSize 회전 윈도 길이(N거래일). 윈도가 N개 미만이면 추세 판단 불가로 본다
     */
    public record Confidence(BigDecimal weakeningDelta, BigDecimal lowThreshold, int windowSize) {

        /** 추세는 두 값 이상에서만 성립한다. */
        private static final int MIN_WINDOW = 2;

        public Confidence {
            Objects.requireNonNull(
                    weakeningDelta, "notifier.filter.confidence.weakening-delta는 필수다");
            Objects.requireNonNull(lowThreshold, "notifier.filter.confidence.low-threshold는 필수다");
            if (windowSize < MIN_WINDOW) {
                throw new IllegalArgumentException(
                        "notifier.filter.confidence.window-size는 2 이상이어야 한다 — 추세는 두 값 이상에서만 성립한다 (현재 값: "
                                + windowSize
                                + ")");
            }
        }
    }
}
