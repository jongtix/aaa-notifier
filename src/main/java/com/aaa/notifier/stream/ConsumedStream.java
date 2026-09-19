package com.aaa.notifier.stream;

import lombok.Getter;

/**
 * 소비 대상 4개 스트림과 그 Consumer Group·컨슈머 이름 계약 (REQ-NOTIFIER-CONSUMER-001, TECHSPEC §5.1).
 *
 * <p>4개 스트림 모두 단일 그룹 {@link #CONSUMER_GROUP}을 사용하고, 컨슈머 이름은 {@code {서비스명}-{스트림역할}} 규칙을 따른다. 컨슈머
 * 이름에는 키 구분자 {@code :}를 의도적으로 쓰지 않는다 — Redis 키 네임스페이스와 컨슈머 식별자를 눈으로 구분하기 위함이다.
 *
 * <p>DLQ 키는 {@code stream:dlq:{원본 스트림명}}이며, 원본 키를 통째로 접미하므로 {@code stream:dlq:stream:tick:domestic}
 * 형태가 된다(analyzer {@code consumer.py}의 {@code DLQ_STREAM} 관례와 동일 — 서비스 간 DLQ 명명을 단일화한다).
 *
 * <p>@MX:ANCHOR: [AUTO] 소비 계층의 유일한 스트림 식별자 원천. 그룹 생성·소비 루프·DLQ 이관이 전부 이 상수를 참조한다.
 *
 * <p>@MX:REASON: fan_in >= 3 (StreamConsumerRunner 그룹 생성, StreamConsumerWorker 소비 루프,
 * DeadLetterPublisher 이관 경로).
 */
@Getter
public enum ConsumedStream {

    /** collector `KisTickPublisher` 국내 틱. */
    TICK_DOMESTIC(
            "stream:tick:domestic", "notifier-tick-domestic", Market.DOMESTIC, PayloadKind.TICK),

    /** collector `KisTickPublisher` 해외 틱. */
    TICK_OVERSEAS(
            "stream:tick:overseas", "notifier-tick-overseas", Market.OVERSEAS, PayloadKind.TICK),

    /** analyzer `inference/publish.py` 국내 신호. */
    SIGNAL_DOMESTIC(
            "stream:signal:domestic",
            "notifier-signal-domestic",
            Market.DOMESTIC,
            PayloadKind.SIGNAL),

    /** analyzer `inference/publish.py` 해외 신호. */
    SIGNAL_OVERSEAS(
            "stream:signal:overseas",
            "notifier-signal-overseas",
            Market.OVERSEAS,
            PayloadKind.SIGNAL);

    /** 4개 스트림이 공유하는 단일 Consumer Group 이름 (REQ-001). */
    public static final String CONSUMER_GROUP = "notifier";

    private static final String DLQ_KEY_PREFIX = "stream:dlq:";

    /** Redis 스트림 키. */
    private final String key;

    /** 이 스트림 전용 컨슈머 이름 ({@code :} 미포함). */
    private final String consumerName;

    /** 스트림 키에서 결정되는 시장 구분. */
    private final Market market;

    /** 페이로드 계약 종류. */
    private final PayloadKind payloadKind;

    ConsumedStream(String key, String consumerName, Market market, PayloadKind payloadKind) {
        this.key = key;
        this.consumerName = consumerName;
        this.market = market;
        this.payloadKind = payloadKind;
    }

    /** 페이로드 계약 종류 — 틱(4필드 엔트리)과 신호(7필드 엔트리)는 파서가 다르다. */
    public enum PayloadKind {
        /** `symbol`/`trId`/`data`/`trace_id` 4필드 엔트리 (REQ-031). */
        TICK,

        /** analyzer `SIGNAL_STREAM_FIELDS` 7필드 엔트리 (REQ-041). */
        SIGNAL
    }

    /** 이 스트림의 DLQ 키 — {@code stream:dlq:{원본 스트림명}} (REQ-021). */
    public String dlqKey() {
        return DLQ_KEY_PREFIX + key;
    }
}
