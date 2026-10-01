package com.aaa.notifier.telegram;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 매매봇 실발송 설정 (SPEC-NOTIFIER-TELEGRAM-001 plan.md §F — 수치 전부 외부화, [D-8]).
 *
 * <p>{@code FilterProperties}와 같은 원칙으로 수치에는 {@code @DefaultValue}를 두지 않는다 — 값은 {@code
 * application.yml} 한 곳에만 있다. 예외는 {@link #enabled}뿐이며 기본값 {@code false}가 드라이런 관문([D-8])이다.
 *
 * <p>토큰·chat_id 존재 검증({@link #requireCredentials()}, REQ-003)은 바인딩 시점이 아니라 자동 구성의 빈 생성 시점에 한다 — 바인딩
 * 실패 보고는 대상 속성의 <b>값</b>을 함께 출력할 수 있어, 비밀 값이 기동 실패 로그에 섞일 여지를 없애기 위해서다.
 *
 * @param enabled 실발송 모드 (기본 {@code false} — 꺼져 있으면 FILTER-001 DRYRUN 싱크가 그대로 쓰인다)
 * @param botToken 매매봇 토큰 ({@code TELEGRAM_TRADE_BOT_TOKEN})
 * @param chatId 수신 chat_id ({@code TELEGRAM_TRADE_CHAT_ID})
 * @param baseUrl Bot API 기준 URL (테스트는 스텁 서버 주소로 바꾼다)
 * @param minSendInterval 같은 chat_id 연속 요청 사이 최소 간격 (REQ-004)
 * @param connectTimeout 연결 타임아웃
 * @param readTimeout 응답 타임아웃
 * @param dispatchBufferCapacity 디스패치 버퍼 상한 — 초과분은 대기 큐로 이관 (plan.md §D.2)
 * @param alertStreamMaxLen {@code stream:alert} 근사 MAXLEN (REQ-041)
 * @param retry 일시 오류 재시도 (REQ-023)
 * @param rateLimit 429 대기·상한 (REQ-022)
 * @param safeMode 자동 진입 임계·프로브 주기 (REQ-031/032)
 * @param queue 대기 큐 보존·요약 목록 상한 (REQ-033)
 */
@ConfigurationProperties(prefix = "notifier.telegram")
public record TelegramProperties(
        @DefaultValue("false") boolean enabled,
        String botToken,
        String chatId,
        String baseUrl,
        Duration minSendInterval,
        Duration connectTimeout,
        Duration readTimeout,
        int dispatchBufferCapacity,
        long alertStreamMaxLen,
        Retry retry,
        RateLimit rateLimit,
        SafeMode safeMode,
        Queue queue) {

    /** 토큰 설정 키 — 기동 실패 메시지에 값 대신 이 이름을 싣는다. */
    static final String BOT_TOKEN_KEY = "notifier.telegram.bot-token";

    /** chat_id 설정 키. */
    static final String CHAT_ID_KEY = "notifier.telegram.chat-id";

    /** 매매봇 토큰이 쓸 수 있는 문자 집합 — Bot API 토큰 형태({@code <bot_id>:<secret>})를 벗어나면 거부한다 (W1). */
    private static final Pattern BOT_TOKEN_CHARSET = Pattern.compile("[0-9A-Za-z:_-]+");

    /**
     * 실발송 모드에서 토큰·chat_id가 비어 있으면 누락된 설정 이름을 밝히고 실패한다 (REQ-003). 값은 출력하지 않는다.
     *
     * @throws IllegalStateException 둘 중 하나라도 비어 있거나, 토큰에 허용되지 않은 문자가 있을 때
     */
    void requireCredentials() {
        requireNotBlank(botToken, BOT_TOKEN_KEY, "TELEGRAM_TRADE_BOT_TOKEN");
        requireSafeCharset(botToken, BOT_TOKEN_KEY);
        requireNotBlank(chatId, CHAT_ID_KEY, "TELEGRAM_TRADE_CHAT_ID");
        Objects.requireNonNull(baseUrl, "notifier.telegram.base-url은 필수다");
        Objects.requireNonNull(minSendInterval, "notifier.telegram.min-send-interval은 필수다");
        Objects.requireNonNull(connectTimeout, "notifier.telegram.connect-timeout은 필수다");
        Objects.requireNonNull(readTimeout, "notifier.telegram.read-timeout은 필수다");
        Objects.requireNonNull(retry, "notifier.telegram.retry는 필수다");
        Objects.requireNonNull(rateLimit, "notifier.telegram.rate-limit은 필수다");
        Objects.requireNonNull(safeMode, "notifier.telegram.safe-mode는 필수다");
        Objects.requireNonNull(queue, "notifier.telegram.queue는 필수다");
        if (dispatchBufferCapacity <= 0) {
            throw new IllegalStateException(
                    "notifier.telegram.dispatch-buffer-capacity는 양수여야 한다 (현재 값: "
                            + dispatchBufferCapacity
                            + ")");
        }
    }

    private static void requireNotBlank(String value, String key, String envName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "실발송 모드(notifier.telegram.enabled=true)에서 "
                            + key
                            + " 설정(환경변수 "
                            + envName
                            + ")이 비어 있다 — 값을 등록하거나 실발송 모드를 끈다");
        }
    }

    /** 허용되지 않은 문자가 있으면 실패한다 (W1) — 메시지에는 설정 키 이름만 싣고 값은 출력하지 않는다. */
    private static void requireSafeCharset(String value, String key) {
        if (!BOT_TOKEN_CHARSET.matcher(value).matches()) {
            throw new IllegalStateException(
                    "실발송 모드(notifier.telegram.enabled=true)에서 "
                            + key
                            + " 설정에 허용되지 않은 문자가 있다 ([0-9A-Za-z:_-]만 허용) — 값을 확인한다");
        }
    }

    /** 토큰은 앞 4자만 드러낸다 (REQ-051) — 기본 record {@code toString}은 토큰 원문을 출력한다. */
    @Override
    public String toString() {
        return "TelegramProperties[enabled="
                + enabled
                + ", botToken="
                + TokenMasker.mask(botToken)
                + ", chatIdPresent="
                + (chatId != null && !chatId.isBlank())
                + ", baseUrl="
                + baseUrl
                + "]";
    }

    /**
     * @param transientRetries 5xx·타임아웃·입출력 오류 재시도 횟수 K (총 시도 = 1 + K)
     * @param backoffInitial 첫 재시도 전 대기
     * @param backoffMultiplier 재시도마다 대기에 곱하는 배수
     */
    public record Retry(int transientRetries, Duration backoffInitial, int backoffMultiplier) {}

    /**
     * @param defaultWait 429 응답에 {@code retry_after}가 없을 때의 대기
     * @param maxCumulativeWait 알림 1건당 429 누적 대기 상한(이번 대기 포함)
     * @param maxResends 알림 1건당 429 재발송 횟수 상한
     */
    public record RateLimit(Duration defaultWait, Duration maxCumulativeWait, int maxResends) {}

    /**
     * @param failureThreshold 자동 진입 임계 N — 연속 실패 카운터가 이 값에 도달하면 {@code AUTO}
     * @param probeCron 프로브 주기 작업 cron (ADR-008)
     */
    public record SafeMode(int failureThreshold, String probeCron) {}

    /**
     * @param retention 대기 큐 항목 보존 기간 — 넘긴 항목은 요약에서 빼고 폐기
     * @param summaryListLimit 요약 본문에 목록으로 표기하는 최대 항목 수
     */
    public record Queue(Duration retention, int summaryListLimit) {}
}
