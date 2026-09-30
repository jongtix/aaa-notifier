package com.aaa.notifier.telegram;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * safe_mode 키의 Redis 구현 (design.md §2.1~§2.3).
 *
 * <p><b>조건부 쓰기는 서버 측 스크립트 한 번으로 한다</b>(design.md §2.2 "구현 스케치") — 읽을 때 받은 <b>원래 문자열</b>(키 없음 포함)과
 * 현재 값이 같을 때만 새 값을 쓴다. 비교를 원래 문자열로 하므로 공백·대소문자 해석 규칙은 자바 쪽({@link SafeModeState})에만 있다. 운영자는 프로세스
 * 밖에서 아무 때나 쓰므로 프로세스 안 잠금으로는 막을 수 없다(research.md §4.3 ①).
 *
 * <p>스크립트 실행 명령군이 라이브 ACL에서 허용되는지는 대조하지 않았다(plan.md §H R11) — 막혀 있으면 {@link AutoEntry#FAILED}로 끝나
 * 발송은 계속되고 WARN이 남는다.
 */
@Slf4j
@RequiredArgsConstructor
public class RedisSafeModeStore implements SafeModeStore {

    /** TECHSPEC §1.3 키 패턴 {@code safe_mode:{service}:{module}}. */
    static final String KEY = "safe_mode:notifier:telegram";

    private static final String AUTO_VALUE = "AUTO";
    private static final String OFF_VALUE = "OFF";

    /** ARGV[1]=기대값이 키 없음이면 "1", ARGV[2]=기대 원래 값, ARGV[3]=새 값. 썼으면 1. */
    private static final RedisScript<Long> COMPARE_AND_SET =
            new DefaultRedisScript<>(
                    "local current = redis.call('GET', KEYS[1])\n"
                            + "if ARGV[1] == '1' then\n"
                            + "  if current then return 0 end\n"
                            + "elseif current ~= ARGV[2] then\n"
                            + "  return 0\n"
                            + "end\n"
                            + "redis.call('SET', KEYS[1], ARGV[3])\n"
                            + "return 1",
                    Long.class);

    private final StringRedisTemplate redis;
    private final TelegramMetrics metrics;

    /** 틱 소비 스레드(버퍼 초과 경로)가 Redis 없이 읽는 마지막 관측값 — 디스패처·프로브가 읽을 때마다 갱신한다. */
    private final AtomicReference<SafeModeState> observed =
            new AtomicReference<>(SafeModeState.OFF);

    @Override
    public SafeModeReading read() {
        String raw;
        try {
            raw = redis.opsForValue().get(KEY);
        } catch (DataAccessException e) {
            log.warn(
                    "[telegram-safe-mode] safe_mode를 읽지 못했다 — OFF로 간주하고 발송을 시도한다(plan.md §D.10) error={}",
                    e.getMessage());
            return SafeModeReading.failed();
        }
        if (!SafeModeState.isRecognized(raw)) {
            log.warn(
                    "[telegram-safe-mode] 알 수 없는 safe_mode 값 — 운영자 kill switch(ON)로 간주한다 raw={} key={}",
                    raw,
                    KEY);
        }
        SafeModeReading reading = SafeModeReading.of(raw);
        remember(reading.state());
        return reading;
    }

    @Override
    public SafeModeState lastObserved() {
        return observed.get();
    }

    @Override
    public AutoEntry enterAuto() {
        SafeModeReading reading = read();
        if (reading.readFailed()) {
            return AutoEntry.FAILED;
        }
        if (reading.state() != SafeModeState.OFF) {
            return AutoEntry.REJECTED;
        }
        try {
            if (compareAndSet(reading.raw(), AUTO_VALUE)) {
                remember(SafeModeState.AUTO);
                return AutoEntry.WRITTEN;
            }
            return AutoEntry.REJECTED;
        } catch (DataAccessException e) {
            log.warn(
                    "[telegram-safe-mode] AUTO 조건부 쓰기 실패 — 다음 소진 때 다시 시도한다 error={}",
                    e.getMessage());
            return AutoEntry.FAILED;
        }
    }

    @Override
    public boolean releaseAuto(SafeModeReading reading) {
        if (reading.readFailed() || reading.state() != SafeModeState.AUTO) {
            return false;
        }
        try {
            if (compareAndSet(reading.raw(), OFF_VALUE)) {
                remember(SafeModeState.OFF);
                return true;
            }
            return false;
        } catch (DataAccessException e) {
            log.warn(
                    "[telegram-safe-mode] OFF 조건부 쓰기 실패 — 다음 프로브 주기에 다시 시도한다 error={}",
                    e.getMessage());
            return false;
        }
    }

    /**
     * 현재 원래 값이 {@code expectedRaw}(키 없음 = {@code null})일 때만 {@code newValue}를 쓴다 — 비교와 쓰기가 한 번의 서버
     * 측 스크립트 실행 안에서 일어난다.
     *
     * @return 썼는지
     */
    boolean compareAndSet(String expectedRaw, String newValue) {
        Long written =
                redis.execute(
                        COMPARE_AND_SET,
                        List.of(KEY),
                        expectedRaw == null ? "1" : "0",
                        expectedRaw == null ? "" : expectedRaw,
                        newValue);
        return written != null && written == 1L;
    }

    private void remember(SafeModeState state) {
        observed.set(state);
        metrics.safeMode(state);
    }
}
