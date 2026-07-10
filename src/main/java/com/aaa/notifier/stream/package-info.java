/**
 * 스트림 컨슈머 패키지 경계(구조적 자리).
 *
 * <p>최종 형태에서 이 패키지는 {@code stream:signal:*}(analyzer)·{@code stream:tick:*}(collector) Redis
 * Streams를 구독하는 컨슈머를 담는다. 그러나 어떤 스트림을 소비할지, 소비 루프 개수, Virtual Thread 여부, BLOCK/COUNT/ACK 타이밍은 본 골격
 * SPEC(FOUNDATION-001) 범위 밖이다.
 *
 * <p>실제 XREADGROUP 소비·스레딩 모델·확인(ACK) 정책은 CONSUMER-001/FILTER-001에서 채운다(REQ-NOTIFIER-FOUNDATION-013,
 * [D-3]). 본 SPEC은 패키지 경계만 제공한다.
 */
package com.aaa.notifier.stream;
