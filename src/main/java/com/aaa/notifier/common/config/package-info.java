/**
 * 공통 설정 패키지 경계(구조적 자리).
 *
 * <p>현재 골격 단계에서는 별도 설정 빈이 없다(actuator 노출·Virtual Threads·Redis 연결은 {@code application.yml}이 담당). 후속
 * SPEC이 필요로 하는 공통 설정 빈(예: Clock 주입, RestClient 구성 등)이 이 패키지에 배치된다.
 */
package com.aaa.notifier.common.config;
