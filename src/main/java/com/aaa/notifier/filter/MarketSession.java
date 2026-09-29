package com.aaa.notifier.filter;

import com.aaa.notifier.stream.Market;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 시장별 고정 정규장 시각 (spec.md §3 — 정밀 캘린더는 범위 밖, 설계 초안 [D-12]).
 *
 * <p>KRX 09:00-15:30 KST, 미국 09:30-16:00 ET. 미국 서머타임은 {@code America/New_York} 시간대 규칙으로 자동 처리된다.
 * 반일장·수능일 지연 개장은 명시적으로 수용하는 리스크다.
 *
 * @param zone 시장 시간대
 * @param open 정규장 개장 시각(시장 현지)
 * @param close 정규장 마감 시각(시장 현지)
 */
public record MarketSession(ZoneId zone, LocalTime open, LocalTime close) {

    /** 한국 표준시 — 체결 관측치의 {@code tradeTime}은 국내·해외 모두 이 시간대다. */
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final MarketSession DOMESTIC =
            new MarketSession(KST, LocalTime.of(9, 0), LocalTime.of(15, 30));
    private static final MarketSession OVERSEAS =
            new MarketSession(
                    ZoneId.of("America/New_York"), LocalTime.of(9, 30), LocalTime.of(16, 0));

    /** 시장 구분에 대응하는 세션. */
    public static MarketSession of(Market market) {
        return market == Market.DOMESTIC ? DOMESTIC : OVERSEAS;
    }

    /** 시장 현지 날짜 기준 '당일'. */
    public LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(zone));
    }

    /** 정규장 길이 (국내·해외 모두 390분). */
    public Duration length() {
        return Duration.between(open, close);
    }

    /**
     * 체결 시각이 개장으로부터 지난 시간. 개장 전이면 음수다.
     *
     * @param tradeAt 체결 시각(임의 시간대 — 시장 시간대로 환산한다)
     */
    public Duration sinceOpen(ZonedDateTime tradeAt) {
        ZonedDateTime local = tradeAt.withZoneSameInstant(zone);
        return Duration.between(local.toLocalDate().atTime(open).atZone(zone), local);
    }

    /**
     * 체결 시각부터 마감까지 남은 시간. 마감 후면 음수다.
     *
     * @param tradeAt 체결 시각(임의 시간대 — 시장 시간대로 환산한다)
     */
    public Duration untilClose(ZonedDateTime tradeAt) {
        ZonedDateTime local = tradeAt.withZoneSameInstant(zone);
        return Duration.between(local, local.toLocalDate().atTime(close).atZone(zone));
    }

    /**
     * 체결 관측치의 KST 체결 시각({@code tradeTime}, 날짜 없음)을 시계의 날짜에 붙여 시각으로 만든다.
     *
     * <p>자정 직전 체결이 자정 직후에 처리되는 경우(해외 정규장은 KST 자정을 넘긴다)를 위해, 조립한 시각이 현재보다 12시간 이상 미래면 전날로 본다.
     *
     * @param kstTradeTime 체결 관측치의 {@code tradeTime}(KST)
     */
    public static ZonedDateTime tradeAt(LocalTime kstTradeTime, Clock clock) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(KST));
        ZonedDateTime candidate = now.toLocalDate().atTime(kstTradeTime).atZone(KST);
        return candidate.isAfter(now.plusHours(12)) ? candidate.minusDays(1) : candidate;
    }

    /**
     * 현재 시각부터 당일 정규장 마감까지 남은 시간. 필터 상태 키의 장 마감 만료(TTL)로 쓴다.
     *
     * @return 이미 마감했으면 0 이하
     */
    public Duration untilClose(Clock clock) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        return Duration.between(now, now.toLocalDate().atTime(close).atZone(zone));
    }
}
