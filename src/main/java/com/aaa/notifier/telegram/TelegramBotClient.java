package com.aaa.notifier.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.DefaultUriBuilderFactory.EncodingMode;

/**
 * 매매봇 Bot API 클라이언트 — Spring {@code RestClient} 직접 호출 (설계 초안 [D-2], 봇 라이브러리·WebClient 미사용).
 *
 * <p><b>예외를 던지지 않는다.</b> 모든 호출은 {@link SendOutcome} 4분류로 끝난다 — 재시도·대기·큐잉 판단은 호출 쪽(디스패처)이 한다.
 *
 * <p><b>토큰은 요청 URL 경로에 들어간다</b>({@code /bot<token>/...}). HTTP 클라이언트 예외 메시지에 URL이 그대로 실리므로, 예외 객체를
 * 로거에 넘기지 않고(스택 트레이스에 메시지가 찍힌다) {@link TokenMasker#scrub}을 거친 문자열만 남긴다(REQ-051).
 *
 * <p>HTTP 클라이언트는 JDK {@link HttpClient}(HTTP/1.1 고정)를 쓴다 — {@code HttpURLConnection}은 연결이 끊긴 POST를
 * 조용히 한 번 더 보낼 수 있어 발송 횟수를 흐린다.
 */
// @MX:ANCHOR: [AUTO] 텔레그램 외부 시스템 통합 지점 — 알림 발송·요약 발송·safe_mode 프로브가 모두 이 클래스를 지난다
// @MX:REASON: 응답 분류(성공/429/일시/영구)가 바뀌면 재시도·큐잉·safe_mode 진입 판단 전체가 달라지고, 오류 경로의 토큰 마스킹이 빠지면 비밀이
// 로그로 샌다
@Slf4j
public class TelegramBotClient implements TelegramApi, AutoCloseable {

    private static final String PARSE_MODE = "HTML";
    private static final int TOO_MANY_REQUESTS = 429;

    private final String botToken;
    private final String chatId;
    private final HttpClient httpClient;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    /**
     * @param baseUrl Bot API 기준 URL
     * @param botToken 매매봇 토큰
     * @param chatId 수신 chat_id
     * @param connectTimeout 연결 타임아웃
     * @param readTimeout 응답 타임아웃
     */
    public TelegramBotClient(
            String baseUrl,
            String botToken,
            String chatId,
            Duration connectTimeout,
            Duration readTimeout) {
        this.botToken = botToken;
        this.chatId = chatId;
        this.httpClient =
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(connectTimeout)
                        .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        // 토큰의 ':'가 %3A로 인코딩되지 않게 URI 인코딩을 끈다 — 토큰 문자 집합([0-9A-Za-z:_-])은 경로에 그대로 둘 수 있다.
        DefaultUriBuilderFactory uriBuilderFactory = new DefaultUriBuilderFactory(baseUrl);
        uriBuilderFactory.setEncodingMode(EncodingMode.NONE);
        this.restClient =
                RestClient.builder()
                        .uriBuilderFactory(uriBuilderFactory)
                        .requestFactory(requestFactory)
                        .build();
    }

    @Override
    public SendOutcome sendMessage(String text) {
        try {
            return restClient
                    .post()
                    .uri("/bot{token}/sendMessage", botToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new SendMessageRequest(chatId, text, PARSE_MODE))
                    .exchange(
                            (request, response) ->
                                    classify(
                                            response.getStatusCode(),
                                            readBody(response.getBody())));
        } catch (RestClientException e) {
            String detail = describe(e);
            log.warn("[telegram-client] sendMessage 입출력 오류 — 일시 오류로 분류 detail={}", detail);
            return SendOutcome.transientFailure(detail);
        }
    }

    @Override
    public boolean probe() {
        try {
            Boolean ok =
                    restClient
                            .get()
                            .uri("/bot{token}/getMe", botToken)
                            .exchange(
                                    (request, response) ->
                                            response.getStatusCode().is2xxSuccessful()
                                                    && isOk(parse(readBody(response.getBody()))));
            return Boolean.TRUE.equals(ok);
        } catch (RestClientException e) {
            log.warn("[telegram-client] getMe 프로브 입출력 오류 detail={}", describe(e));
            return false;
        }
    }

    private SendOutcome classify(HttpStatusCode status, String body) {
        TelegramResponse response = parse(body);
        if (status.is2xxSuccessful()) {
            if (response != null && Boolean.FALSE.equals(response.ok())) {
                return SendOutcome.permanentFailure("HTTP " + status.value() + " ok=false");
            }
            return SendOutcome.success(response == null ? null : response.messageId());
        }
        String detail = "HTTP " + status.value() + " " + describe(response);
        if (status.value() == TOO_MANY_REQUESTS) {
            return SendOutcome.rateLimited(response == null ? null : response.retryAfter(), detail);
        }
        if (status.is4xxClientError()) {
            log.warn("[telegram-client] sendMessage 영구 오류 {}", detail);
            return SendOutcome.permanentFailure(detail);
        }
        return SendOutcome.transientFailure(detail);
    }

    private static String readBody(InputStream body) throws IOException {
        return new String(body.readAllBytes(), StandardCharsets.UTF_8);
    }

    /** 본문을 해석하지 못하면 {@code null} — 5xx HTML 등. */
    private TelegramResponse parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(body, TelegramResponse.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static boolean isOk(TelegramResponse response) {
        return response != null && Boolean.TRUE.equals(response.ok());
    }

    private String describe(TelegramResponse response) {
        if (response == null || response.description() == null) {
            return "";
        }
        return TokenMasker.scrub(response.description(), botToken);
    }

    /** 예외 원인 사슬의 클래스 이름과 마스킹된 메시지만 남긴다 — 예외 객체 자체는 로그로 넘기지 않는다. */
    private String describe(RestClientException e) {
        StringBuilder builder = new StringBuilder(e.getClass().getSimpleName());
        builder.append(": ").append(TokenMasker.scrub(e.getMessage(), botToken));
        Throwable cause = e.getCause();
        if (cause != null) {
            builder.append(" (cause ")
                    .append(cause.getClass().getSimpleName())
                    .append(": ")
                    .append(TokenMasker.scrub(cause.getMessage(), botToken))
                    .append(')');
        }
        return builder.toString();
    }

    @Override
    public void close() {
        httpClient.close();
    }

    /** {@code sendMessage} 요청 본문 — {@code reply_markup} 필드 자체가 없다 (REQ-012). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SendMessageRequest(
            @JsonProperty("chat_id") String chatId,
            @JsonProperty("text") String text,
            @JsonProperty("parse_mode") String parseMode) {}

    /** Bot API 공통 응답 envelope (api-specs/telegram/01 — 공식 문서 인용, 미실측). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TelegramResponse(
            @JsonProperty("ok") Boolean ok,
            @JsonProperty("result") JsonNode result,
            @JsonProperty("description") String description,
            @JsonProperty("parameters") Parameters parameters) {

        Long messageId() {
            if (result == null || !result.hasNonNull("message_id")) {
                return null;
            }
            return result.get("message_id").asLong();
        }

        Duration retryAfter() {
            if (parameters == null || parameters.retryAfter() == null) {
                return null;
            }
            return Duration.ofSeconds(parameters.retryAfter());
        }
    }

    /** 실패 응답 부가 정보 {@code ResponseParameters}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Parameters(@JsonProperty("retry_after") Integer retryAfter) {}
}
