package com.aaa.notifier.telegram;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.notifier.telegram.SendOutcome.Kind;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Bot API 클라이언트 — 응답 4분류·요청 모양·토큰 마스킹 (REQ-012·021~024·032·051 클라이언트 경로, AC-06·AC-10·AC-15).
 *
 * <p>텔레그램은 프로세스 안 WireMock 스텁 서버로 대체한다 — 실제 {@code api.telegram.org} 호출은 없다. 컨테이너가 없어 단위 계층에서 돈다.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("TelegramBotClient — Bot API 응답 4분류·토큰 마스킹")
class TelegramBotClientTest {

    static final String TOKEN = "123456789:TESTSECRETVALUE";
    static final String SECRET = "TESTSECRETVALUE";
    static final String CHAT_ID = "987654321";
    static final String SEND_PATH = "/bot" + TOKEN + "/sendMessage";
    static final String GET_ME_PATH = "/bot" + TOKEN + "/getMe";

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
                        Duration.ofMillis(500));
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

    @Nested
    @DisplayName("sendMessage 응답 분류")
    class SendClassification {

        @Test
        @DisplayName("REQ-021 — 200 + ok:true는 성공이고 result.message_id를 돌려준다")
        void ok_isSuccessWithMessageId() {
            stubSend(200, "{\"ok\":true,\"result\":{\"message_id\":777,\"date\":1}}");

            SendOutcome outcome = client.sendMessage("hello");

            assertThat(outcome.kind()).isEqualTo(Kind.SUCCESS);
            assertThat(outcome.messageId()).isEqualTo(777L);
        }

        @Test
        @DisplayName("200인데 본문을 해석할 수 없으면 성공으로 보고 message_id는 비운다 (중복 발송 회피)")
        void okUnparseableBody_isSuccessWithoutId() {
            stubSend(200, "not-json");

            SendOutcome outcome = client.sendMessage("hello");

            assertThat(outcome.kind()).isEqualTo(Kind.SUCCESS);
            assertThat(outcome.messageId()).isNull();
        }

        @Test
        @DisplayName("REQ-022 — 429는 레이트 리밋이며 parameters.retry_after를 대기 시간으로 싣는다")
        void tooManyRequests_carriesRetryAfter() {
            stubSend(
                    429,
                    "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests: retry"
                            + " after 3\",\"parameters\":{\"retry_after\":3}}");

            SendOutcome outcome = client.sendMessage("hello");

            assertThat(outcome.kind()).isEqualTo(Kind.RATE_LIMITED);
            assertThat(outcome.retryAfter()).isEqualTo(Duration.ofSeconds(3));
        }

        @Test
        @DisplayName("REQ-022 — retry_after가 없는 429는 대기 시간을 비워 둔다 (호출 쪽이 기본값 적용)")
        void tooManyRequestsWithoutRetryAfter_hasNoWait() {
            stubSend(429, "{\"ok\":false,\"error_code\":429}");

            SendOutcome outcome = client.sendMessage("hello");

            assertThat(outcome.kind()).isEqualTo(Kind.RATE_LIMITED);
            assertThat(outcome.retryAfter()).isNull();
        }

        @ParameterizedTest(name = "HTTP {0}")
        @ValueSource(ints = {500, 502, 503})
        @DisplayName("REQ-023 — 5xx는 일시 오류다 (본문이 JSON이 아니어도)")
        void serverError_isTransient(int status) {
            stubSend(status, "<html>bad gateway</html>");

            assertThat(client.sendMessage("hello").kind()).isEqualTo(Kind.TRANSIENT);
        }

        @ParameterizedTest(name = "HTTP {0}")
        @ValueSource(ints = {400, 401, 403, 404})
        @DisplayName("REQ-024 / AC-10 — 429 외 4xx는 영구 오류이며 description을 싣는다")
        void clientError_isPermanent(int status) {
            stubSend(
                    status,
                    "{\"ok\":false,\"error_code\":" + status + ",\"description\":\"nope\"}");

            SendOutcome outcome = client.sendMessage("hello");

            assertThat(outcome.kind()).isEqualTo(Kind.PERMANENT);
            assertThat(outcome.detail()).contains("nope");
        }

        @Test
        @DisplayName("AC-09 ② — 연결 끊김은 일시 오류다")
        void connectionReset_isTransient() {
            stub.stubFor(
                    post(urlPathEqualTo(SEND_PATH))
                            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

            assertThat(client.sendMessage("hello").kind()).isEqualTo(Kind.TRANSIENT);
        }

        @Test
        @DisplayName("AC-09 ② — 응답 타임아웃은 일시 오류다")
        void readTimeout_isTransient() {
            stub.stubFor(
                    post(urlPathEqualTo(SEND_PATH))
                            .willReturn(aResponse().withStatus(200).withFixedDelay(1500)));

            assertThat(client.sendMessage("hello").kind()).isEqualTo(Kind.TRANSIENT);
        }

        @Test
        @DisplayName("AC-09 ② — 연결 거부는 일시 오류다")
        void connectionRefused_isTransient() {
            stub.stop();

            assertThat(client.sendMessage("hello").kind()).isEqualTo(Kind.TRANSIENT);
        }
    }

    @Nested
    @DisplayName("요청 모양")
    class RequestShape {

        @Test
        @DisplayName(
                "chat_id·text·parse_mode=HTML을 JSON으로 보내고 reply_markup은 보내지 않는다 (REQ-012, AC-06)")
        void sendsHtmlWithoutReplyMarkup() {
            stubSend(200, "{\"ok\":true,\"result\":{\"message_id\":1}}");

            client.sendMessage("<b>BUY</b>");

            stub.verify(
                    postRequestedFor(urlPathEqualTo(SEND_PATH))
                            .withRequestBody(matchingJsonPath("$.chat_id", equalTo(CHAT_ID)))
                            .withRequestBody(matchingJsonPath("$.text", equalTo("<b>BUY</b>")))
                            .withRequestBody(matchingJsonPath("$.parse_mode", equalTo("HTML"))));
            List<LoggedRequest> requests =
                    stub.findAll(postRequestedFor(urlPathEqualTo(SEND_PATH)));
            assertThat(requests).hasSize(1);
            assertThat(requests.getFirst().getBodyAsString()).doesNotContain("reply_markup");
        }
    }

    @Nested
    @DisplayName("getMe 프로브 (REQ-032)")
    class Probe {

        @Test
        @DisplayName("200 + ok:true면 성공")
        void ok_isSuccess() {
            stub.stubFor(
                    get(urlPathEqualTo(GET_ME_PATH))
                            .willReturn(
                                    aResponse()
                                            .withStatus(200)
                                            .withBody("{\"ok\":true,\"result\":{\"id\":1}}")));

            assertThat(client.probe()).isTrue();
        }

        @Test
        @DisplayName("503·ok:false·연결 거부는 모두 실패")
        void failures_areFalse() {
            stub.stubFor(get(urlPathEqualTo(GET_ME_PATH)).willReturn(aResponse().withStatus(503)));
            assertThat(client.probe()).isFalse();

            stub.stubFor(
                    get(urlPathEqualTo(GET_ME_PATH))
                            .willReturn(aResponse().withStatus(200).withBody("{\"ok\":false}")));
            assertThat(client.probe()).isFalse();

            stub.stop();
            assertThat(client.probe()).isFalse();
        }
    }

    @Test
    @DisplayName("AC-15 — 성공·429·503·400·연결 거부·타임아웃·getMe 실패 경로 전체에서 로그·결과에 토큰 원문이 없다")
    void noTokenLeak_acrossAllPaths(CapturedOutput output) {
        StringBuilder details = new StringBuilder();
        stubSend(200, "{\"ok\":true,\"result\":{\"message_id\":1}}");
        details.append(client.sendMessage("a").detail());
        stubSend(429, "{\"ok\":false,\"parameters\":{\"retry_after\":1}}");
        details.append(client.sendMessage("a").detail());
        stubSend(503, "down");
        details.append(client.sendMessage("a").detail());
        stubSend(400, "{\"ok\":false,\"description\":\"Bad Request\"}");
        details.append(client.sendMessage("a").detail());
        stub.stubFor(
                post(urlPathEqualTo(SEND_PATH))
                        .willReturn(aResponse().withStatus(200).withFixedDelay(1500)));
        details.append(client.sendMessage("a").detail());
        stub.stubFor(get(urlPathEqualTo(GET_ME_PATH)).willReturn(aResponse().withStatus(500)));
        client.probe();
        stub.stop();
        details.append(client.sendMessage("a").detail());
        client.probe();

        assertThat(details.toString()).doesNotContain(SECRET);
        assertThat(output.getAll()).doesNotContain(SECRET).contains("1234****");
    }
}
