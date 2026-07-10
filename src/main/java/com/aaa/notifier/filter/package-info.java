/**
 * 필터 파이프라인 패키지 경계(구조적 자리).
 *
 * <p>최종 형태에서 이 패키지는 6단계 필터 파이프라인(가격 밴드 판정·히스테리시스·확증·쿨다운·confidence 방향성·Tier 분류/라우팅)과 그 필터 상태({@code
 * filter:*:{종목코드}:{horizon}} Redis 키)를 담는다. 실제 필터 로직·상태 키 읽기/쓰기는 본 골격 SPEC 범위 밖이다.
 *
 * <p>필터 로직 일체는 FILTER-001 소관이다. 본 SPEC은 패키지 경계만 제공한다.
 */
package com.aaa.notifier.filter;
