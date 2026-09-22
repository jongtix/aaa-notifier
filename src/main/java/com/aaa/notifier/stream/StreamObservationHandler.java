package com.aaa.notifier.stream;

/**
 * 파싱된 관측치를 받는 단일 인프로세스 핸들러 경계 (REQ-NOTIFIER-CONSUMER-051, plan.md §C.3).
 *
 * <p><b>호출은 소비 스레드에서 동기적으로, 확인응답(XACK) 이전에</b> 이루어진다. 이 순서가 REQ-013의 at-least-once 계약이 성립하기 위한 전제다
 * — 큐나 비동기 이벤트 버스를 끼우면 ACK 시점이 처리 완료와 분리되어 계약이 조용히 깨진다(plan.md §C.3에서 {@code BlockingQueue}와 {@code
 * ApplicationEventPublisher}를 기각한 이유).
 *
 * <p>구현이 예외로 종료하면 해당 메시지는 확인응답되지 않고 미확인 상태로 남아 다음 회차의 재소유 대상이 된다(REQ-013).
 *
 * <p><b>재전달은 엔트리 단위이지 관측치 단위가 아니다.</b> 틱 배치 프레임 1건({@code stream:tick:*}의 엔트리 1개)은 파싱되면 관측치 여러 건이
 * 된다({@link TickPayloadParser#parse}). 이 핸들러가 그중 한 건을 처리한 뒤 다음 건에서 예외를 던지면, Redis 관점에서는 엔트리 전체가 여전히
 * 미확인이므로 재소유 시 <b>같은 엔트리의 모든 관측치가 처음부터 다시</b> 전달된다 — 이미 성공적으로 처리된 앞선 관측치까지 실패한 관측치·그 뒤의 관측치와 함께
 * 재전달된다. 그래서 이 계약은 "메시지당 최소 1회"가 아니라 "엔트리당 최소 1회"이며, 구현체는 개별 관측치 단위로 멱등해야 한다 — 배치 전체가 통으로 재시도되어도 이미
 * 처리된 관측치의 재처리가 안전해야 한다는 뜻이다(FILTER-001이 유의할 지점).
 *
 * <p><b>느려지면 안 된다</b>: {@code stream:tick:*}는 MAXLEN ~5,000(실측 약 100초치 버퍼)이므로 소비가 이보다 느리면 미소비 메시지가
 * 트리밍으로 사라진다 — 재시도 대상이 아니라 소실이다(plan.md §C.4).
 *
 * <p>FILTER-001은 이 포트의 구현체를 제공하기만 하면 되고 소비 계층은 무변경이다. 구현체가 없으면 무동작 기본 구현이 주입되어 이 계층이 단독 배포·운용 가능하다.
 */
public interface StreamObservationHandler {

    /**
     * 체결 관측치 1건을 처리한다.
     *
     * <p><b>구현 의무(REQ-051 후단)</b>: 체결가를 밴드 판정·임계 비교 등 수치 판단에 쓰기 전에 {@code
     * observation.price().scale() == observation.priceScale()} 성립 여부를 확인하고, 불일치하면 그 관측치를 수치 판단에서
     * 제외하거나 의심 값으로 표시해야 한다. 소비 계층은 값을 보정하지 않으므로, 이 확인을 건너뛴 구현체는 비스케일 정수({@code 2984700})를 {@code
     * 298.4700}과 같은 척도의 가격으로 오인해 오탐 알림을 낸다. {@code priceScale()}이 {@code null}인 국내 체결은 이 확인의 대상이
     * 아니다.
     *
     * @param observation 체결 관측치
     */
    void onTradeTick(TradeTickObservation observation);

    /**
     * 호가 패스스루 관측치 1건을 처리한다 (필드 미해석 원시 문자열).
     *
     * @param observation 호가 패스스루 관측치
     */
    void onQuote(QuoteObservation observation);

    /**
     * 신호 관측치 1건을 처리한다.
     *
     * @param observation 신호 관측치
     */
    void onSignal(SignalObservation observation);
}
