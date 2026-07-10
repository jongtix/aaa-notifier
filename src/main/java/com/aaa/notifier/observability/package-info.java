/**
 * 관측성 패키지 경계(구조적 자리).
 *
 * <p>최종 형태에서 이 패키지는 커스텀 Micrometer 메트릭(컨슈머 lag·필터 카운터·발송 히스토그램)을 담는다. 커스텀 메트릭 세트와 vmalert 룰은 본 골격
 * SPEC 범위 밖이며 OBSV-001(aaa-infra 동반) 소관이다.
 *
 * <p>본 SPEC은 패키지 경계와 actuator {@code /actuator/prometheus} 노출(REQ-NOTIFIER-FOUNDATION-010)만 세운다.
 */
package com.aaa.notifier.observability;
