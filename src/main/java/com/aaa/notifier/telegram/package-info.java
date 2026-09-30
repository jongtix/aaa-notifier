/**
 * 매매봇 텔레그램 실발송 (SPEC-NOTIFIER-TELEGRAM-001).
 *
 * <p>필터 파이프라인의 결정 싱크 포트({@code AlertDecisionSink})를 실발송 구현으로 대체한다. 실발송 모드({@code
 * notifier.telegram.enabled})는 기본 꺼짐이며, 꺼져 있으면 이 패키지의 빈이 하나도 올라오지 않고 FILTER-001 DRYRUN 싱크가 그대로 쓰인다.
 *
 * <ul>
 *   <li>{@link com.aaa.notifier.telegram.TelegramBotClient} — Spring {@code RestClient} 직접
 *       호출([D-2]). 봇 라이브러리· WebClient는 쓰지 않는다. 토큰은 오류 경로 전체에서 마스킹한다.
 *   <li>{@link com.aaa.notifier.telegram.TelegramDispatcher} — 결정 수신과 텔레그램 왕복을 분리하는 단일 디스패처(페이싱·429
 *       상한·백오프·safe_mode 자동 진입·요약).
 *   <li>{@link com.aaa.notifier.telegram.RedisSafeModeStore} — {@code safe_mode:notifier:telegram}
 *       조건부 쓰기.
 *   <li>{@link com.aaa.notifier.telegram.SafeModeProbe} — 1분 cron 프로브(AUTO만 해제).
 * </ul>
 *
 * <p>인라인 버튼·콜백 수신은 Phase 4(trader) 소관이다 — 이 패키지는 {@code reply_markup}을 보내지 않는다.
 */
package com.aaa.notifier.telegram;
