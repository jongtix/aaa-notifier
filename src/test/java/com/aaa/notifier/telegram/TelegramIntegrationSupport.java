package com.aaa.notifier.telegram;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.aaa.notifier.filter.AlertDecision;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import java.util.List;
import java.util.Objects;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;

/** 실발송 통합 테스트 공용 — 스텁 서버·테스트 수치·DB/Redis 정리. 실제 {@code api.telegram.org}는 호출하지 않는다. */
final class TelegramIntegrationSupport {

    static final String TOKEN = "123456789:TESTSECRETVALUE";
    static final String SECRET = "TESTSECRETVALUE";
    static final String CHAT_ID = "987654321";
    static final String SEND_PATH = "/bot" + TOKEN + "/sendMessage";
    static final String GET_ME_PATH = "/bot" + TOKEN + "/getMe";

    private TelegramIntegrationSupport() {}

    /** 실발송 모드 + 빠른 테스트 수치. 프로브 cron은 테스트 중 발화하지 않도록 1월 1일 03시로 둔다(테스트가 직접 호출). */
    static void register(DynamicPropertyRegistry registry, WireMockServer stub) {
        registry.add("notifier.telegram.enabled", () -> "true");
        registry.add("notifier.telegram.base-url", stub::baseUrl);
        registry.add("notifier.telegram.bot-token", () -> TOKEN);
        registry.add("notifier.telegram.chat-id", () -> CHAT_ID);
        registry.add("notifier.telegram.min-send-interval", () -> "200ms");
        registry.add("notifier.telegram.connect-timeout", () -> "1s");
        registry.add("notifier.telegram.read-timeout", () -> "1s");
        registry.add("notifier.telegram.retry.backoff-initial", () -> "50ms");
        registry.add("notifier.telegram.rate-limit.default-wait", () -> "100ms");
        registry.add("notifier.telegram.safe-mode.probe-cron", () -> "0 0 3 1 1 *");
        registry.add("notifier.filter.enabled", () -> "true");
    }

    static void respondToSend(WireMockServer stub, ResponseDefinitionBuilder response) {
        stub.stubFor(post(urlPathEqualTo(SEND_PATH)).willReturn(response));
    }

    static ResponseDefinitionBuilder ok(long messageId) {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"ok\":true,\"result\":{\"message_id\":" + messageId + "}}");
    }

    static void respondToGetMe(WireMockServer stub, int status) {
        stub.stubFor(
                get(urlPathEqualTo(GET_ME_PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(status)
                                        .withBody("{\"ok\":" + (status == 200) + "}")));
    }

    /** 이전 테스트의 흔적을 지우고 종목 1건을 넣어 {@code stocks.id}를 돌려준다. */
    static long resetState(JdbcTemplate jdbcTemplate, StringRedisTemplate redis) {
        redis.delete(
                List.of(RedisSafeModeStore.KEY, RedisPendingQueue.KEY, RedisAlertPublisher.KEY));
        jdbcTemplate.update("DELETE FROM notification_log");
        jdbcTemplate.update("DELETE FROM signal_price_bands");
        jdbcTemplate.update("DELETE FROM daily_ohlcv");
        jdbcTemplate.update("DELETE FROM stocks");
        jdbcTemplate.update(
                "INSERT INTO stocks (symbol, market, asset_type, active, created_at, updated_at)"
                        + " VALUES ('005930', 'KOSPI', 'STOCK', TRUE, NOW(), NOW())");
        return Objects.requireNonNull(
                jdbcTemplate.queryForObject("SELECT id FROM stocks", Long.class));
    }

    /** 결정의 종목을 실제 {@code stocks.id}로 바꾼다(FK). */
    static AlertDecision withStock(AlertDecision decision, long stockId) {
        return new AlertDecision(
                stockId,
                decision.symbol(),
                decision.market(),
                decision.horizon(),
                decision.fromGrade(),
                decision.toGrade(),
                decision.tier(),
                decision.transitionType(),
                decision.score(),
                decision.confidence(),
                decision.lowConfidenceFlag(),
                decision.triggerPrice(),
                decision.prevClose(),
                decision.tradeDate(),
                decision.suppressionReason(),
                decision.traceId());
    }

    static int rowCount(JdbcTemplate jdbcTemplate, String eventType) {
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM notification_log WHERE event_type = ?",
                        Integer.class,
                        eventType);
        return count == null ? 0 : count;
    }
}
