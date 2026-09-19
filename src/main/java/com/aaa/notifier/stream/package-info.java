/**
 * Redis Streams 소비 계층 (SPEC-NOTIFIER-CONSUMER-001).
 *
 * <p>collector {@code stream:tick:*} 2개와 analyzer {@code stream:signal:*} 2개, 총 4개 스트림을 단일 Consumer
 * Group {@code notifier}로 소비한다. 스트림당 가상 스레드 1개가 {@code XPENDING 판독 → XCLAIM 재소유 → XREADGROUP 신규 읽기}
 * 회차를 반복하고, 하류 전달이 정상 반환한 뒤에만 확인응답한다.
 *
 * <p><b>이 패키지는 소비 계층에서 끝난다.</b> 밴드 판정·히스테리시스·확증·쿨다운·confidence 추세·Tier 라우팅·텔레그램 발송·{@code filter:*}
 * 키 접근·{@code notification_log} 쓰기·{@code stream:alert} 발행은 전부 FILTER-001/TELEGRAM-001 소관이며, 그 경계는
 * {@code StreamBoundaryGuardTest}가 빌드 타임에 강제한다.
 *
 * <p>하류 연결점은 {@link com.aaa.notifier.stream.StreamObservationHandler} 하나다. FILTER-001은 이 포트의 구현체를
 * 빈으로 올리기만 하면 되고 소비 계층은 무변경이다 — 구현체가 없으면 무동작 기본 구현이 주입되어 이 계층만으로도 단독 배포·운용된다.
 *
 * <p>주의할 계약 세 가지:
 *
 * <ul>
 *   <li><b>배치 분할</b> — KIS 프레임의 배치 건수는 발행 시점에 버려지므로 {@code 총 필드 수 ÷ trId별 레코드 필드 수}로 재유도한다. 빠뜨리면 국내
 *       체결 배치의 6/7이 조용히 유실된다.
 *   <li><b>해외 심볼 정규화</b> — 틱 엔트리의 {@code symbol}은 KIS tr_key({@code DNASAAPL})이고 신호 엔트리의 {@code
 *       symbol}은 {@code stocks.symbol}({@code AAPL})이다. 정규 심볼은 페이로드에서 직접 취한다.
 *   <li><b>{@code ZDIV}</b> — 소수점 자리수이지 배율 지수가 아니다. 배율 연산은 이미 스케일된 값을 이중 스케일링해 오염시킨다.
 * </ul>
 */
package com.aaa.notifier.stream;
