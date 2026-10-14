package com.aaa.notifier.telegram;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.notifier.telegram.SendOutcome.Kind;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

/**
 * Bot API 요청 본문·응답 파싱 계약 (SPEC-INFRA-BOOT4-001 REQ-BOOT4-011, AC-BOOT4-011).
 *
 * <p>요청 본문은 운영 {@link TelegramBotClient}가 직접 만든 {@code RestClient}의 메시지 변환이 직렬화한다 — Spring 관리 매퍼도,
 * 클래스의 응답 읽기 전용 매퍼도 거치지 않는 세 번째 경로다. 그래서 WireMock이 받은 본문 그대로를 Boot 3.5.16 기준 골든과 JSON 의미
 * 비교(JSONassert {@code STRICT} — 멤버 이름·값·값 표현이 같고 추가·누락 없음, 객체 멤버 순서만 무시)로 판정한다.
 *
 * <p>응답 파싱은 운영 클라이언트가 읽어 낸 {@link SendOutcome} 값으로만 단언한다 — Jackson 타입을 쓰지 않는다(acceptance.md §A.1).
 */
@DisplayName("TelegramBotClient — sendMessage 요청 본문·응답 파싱 계약 (Boot 3.5 기준 골든)")
class TelegramBotClientWireFormatContractTest {

    private static final String TOKEN = "123456789:TESTSECRETVALUE";
    private static final String CHAT_ID = "-1001234567890";
    private static final String SEND_PATH = "/bot" + TOKEN + "/sendMessage";
    private static final String GET_ME_PATH = "/bot" + TOKEN + "/getMe";

    /** 실제 Bot API 성공 응답 모양 — 클라이언트가 쓰지 않는 멤버가 섞여 있다. */
    private static final String SUCCESS_RESPONSE =
            """
            {"ok":true,"result":{"message_id":4242,"from":{"id":123456789,"is_bot":true,\
            "first_name":"aaa","username":"aaa_trade_bot"},"chat":{"id":-1001234567890,\
            "type":"supergroup"},"date":1759152665,"text":"hi"}}""";

    private WireMockServer stub;
    private TelegramBotClient client;

    @BeforeEach
    void setUp() {
        stub = new WireMockServer(options().dynamicPort());
        stub.start();
        client =
                new TelegramBotClient(
                        stub.baseUrl(),
                        TOKEN,
                        CHAT_ID,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1));
    }

    @AfterEach
    void tearDown() {
        client.close();
        stub.stop();
    }

    private void stubSend(int status, String body) {
        stub.stubFor(
                post(urlPathEqualTo(SEND_PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(status)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(body)));
    }

    private LoggedRequest onlySendRequest() {
        List<LoggedRequest> requests = stub.findAll(postRequestedFor(urlPathEqualTo(SEND_PATH)));
        assertThat(requests).hasSize(1);
        return requests.getFirst();
    }

    @Test
    @DisplayName("요청 본문이 Boot 3.5 골든과 JSON 의미상 같다 (HTML·특수문자·한글·줄바꿈 포함)")
    void sendMessageRequestBody() throws Exception {
        stubSend(200, SUCCESS_RESPONSE);

        client.sendMessage("<b>[매수]</b> BRK.B \"D20\" & 412.5000\n저확신 ⚠");

        // Arrange
        LoggedRequest request = onlySendRequest();

        // Assert
        assertThat(request.getHeader("Content-Type")).startsWith("application/json");
        JSONAssert.assertEquals(
                """
                {"chat_id":"-1001234567890",\
                "text":"<b>[매수]</b> BRK.B \\"D20\\" & 412.5000\\n저확신 ⚠",\
                "parse_mode":"HTML"}""",
                request.getBodyAsString(),
                JSONCompareMode.STRICT);
    }

    @Test
    @DisplayName("성공 응답 — 모르는 멤버를 무시하고 message_id를 읽는다")
    void parsesSuccessResponse() {
        stubSend(200, SUCCESS_RESPONSE);

        SendOutcome outcome = client.sendMessage("hi");

        assertThat(outcome.kind()).isEqualTo(Kind.SUCCESS);
        assertThat(outcome.messageId()).isEqualTo(4242L);
    }

    @Test
    @DisplayName("429 응답 — parameters.retry_after를 대기 시간으로 읽는다")
    void parsesRateLimitedResponse() {
        stubSend(
                429,
                """
                {"ok":false,"error_code":429,"description":"Too Many Requests: retry after 7",\
                "parameters":{"retry_after":7}}""");

        SendOutcome outcome = client.sendMessage("hi");

        assertThat(outcome.kind()).isEqualTo(Kind.RATE_LIMITED);
        assertThat(outcome.retryAfter()).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    @DisplayName("HTTP 200 + ok:false 응답 — 영구 오류로 읽는다")
    void parsesOkFalseResponse() {
        stubSend(200, "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request\"}");

        assertThat(client.sendMessage("hi").kind()).isEqualTo(Kind.PERMANENT);
    }

    @Test
    @DisplayName("getMe 응답 ok:true — 프로브가 참이다")
    void parsesGetMeResponse() {
        stub.stubFor(
                get(urlPathEqualTo(GET_ME_PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                                {"ok":true,"result":{"id":123456789,"is_bot":true,\
                                                "first_name":"aaa","username":"aaa_trade_bot",\
                                                "can_join_groups":true}}""")));

        assertThat(client.probe()).isTrue();
    }
}
