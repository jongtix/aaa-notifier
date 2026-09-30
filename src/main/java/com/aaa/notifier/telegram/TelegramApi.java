package com.aaa.notifier.telegram;

/** 매매봇 Bot API 포트 — 디스패처·프로브가 이 두 호출만 쓴다. 실구현은 {@link TelegramBotClient}, 테스트는 스크립트 가짜를 쓴다. */
public interface TelegramApi {

    /**
     * 수신 chat_id로 HTML 서식 메시지 1건을 보낸다. 예외를 던지지 않고 결과를 분류해 돌려준다.
     *
     * @param text 이스케이프된 HTML 본문
     * @return 응답 분류
     */
    SendOutcome sendMessage(String text);

    /**
     * 부작용 없는 경량 프로브({@code getMe}). HTTP 200 + {@code ok:true}만 성공이다 (REQ-032).
     *
     * @return 성공 여부
     */
    boolean probe();
}
