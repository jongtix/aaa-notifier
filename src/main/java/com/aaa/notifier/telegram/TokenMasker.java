package com.aaa.notifier.telegram;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 매매봇 토큰 마스킹 (REQ-NOTIFIER-TELEGRAM-051, ADR-011 FRONT_ONLY — 앞 4자 외 마스킹).
 *
 * <p>토큰은 Bot API 요청 URL 경로({@code /bot<token>/sendMessage})에 들어가므로 HTTP 클라이언트 예외 메시지에 그대로 실린다. 로그로
 * 나가는 모든 오류 문자열은 {@link #scrub}을 거쳐야 한다.
 */
final class TokenMasker {

    private static final int VISIBLE_PREFIX = 4;
    private static final String HIDDEN = "****";

    private TokenMasker() {}

    /**
     * 토큰을 앞 4자만 남기고 가린다. 4자 이하·빈 값·{@code null}은 전부 가린다.
     *
     * @param token 매매봇 토큰
     * @return 마스킹된 표현
     */
    static String mask(String token) {
        if (token == null || token.length() <= VISIBLE_PREFIX) {
            return HIDDEN;
        }
        return token.substring(0, VISIBLE_PREFIX) + HIDDEN;
    }

    /**
     * 문장 안의 토큰 원문·URL 인코딩 형태·{@code :} 뒤 비밀 부분을 모두 마스킹 표현으로 바꾼다.
     *
     * @param text 로그·예외 메시지 원문 ({@code null}이면 빈 문자열)
     * @param token 매매봇 토큰 (비어 있으면 치환하지 않는다)
     * @return 토큰이 드러나지 않는 문장
     */
    static String scrub(String text, String token) {
        if (text == null) {
            return "";
        }
        if (token == null || token.isBlank()) {
            return text;
        }
        String masked = mask(token);
        String result =
                text.replace(token, masked)
                        .replace(URLEncoder.encode(token, StandardCharsets.UTF_8), masked);
        int colon = token.indexOf(':');
        if (colon >= 0 && colon < token.length() - 1) {
            result = result.replace(token.substring(colon + 1), HIDDEN);
        }
        return result;
    }
}
