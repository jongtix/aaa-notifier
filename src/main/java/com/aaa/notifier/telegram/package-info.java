/**
 * 텔레그램 발송 클라이언트 패키지 경계(구조적 자리).
 *
 * <p>발송 클라이언트 아키텍처는 Spring {@code RestClient} 직접 HTTP 호출 방식으로 확정되어 있다([D-2]) — 3자 텔레그램 봇 라이브러리(미사용
 * Long Polling/Update 파싱 표면 + CVE 노출)는 기각됐다. {@code RestClient}는 이미 spring-web starter에 포함되어 별도
 * 의존성이 없다.
 *
 * <p>단, 본 골격 SPEC(FOUNDATION-001)은 이 아키텍처 선택만 확정하고 {@code sendMessage} 구현·요청/응답 DTO 정의· {@code
 * api-specs/telegram/} 명세 수집은 하지 않는다(REQ-NOTIFIER-FOUNDATION-014). 실제 발송은 TELEGRAM-001 소관이다. 텔레그램 봇
 * 라이브러리 의존성을 추가해서는 안 된다.
 */
package com.aaa.notifier.telegram;
