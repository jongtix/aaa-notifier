package com.aaa.notifier.filter;

import java.math.BigDecimal;
import java.util.Objects;
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
 */
@ConfigurationProperties(prefix = "notifier.filter")
public record FilterProperties(
        @DefaultValue("true") boolean enabled, ReferenceLoad referenceLoad, Band band) {

    public FilterProperties {
        Objects.requireNonNull(referenceLoad, "notifier.filter.reference-load는 필수다");
        Objects.requireNonNull(band, "notifier.filter.band는 필수다");
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
}
