package com.aaa.notifier.stream;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code stream:signal:*} 엔트리를 신호 관측치로 변환하는 파서 (REQ-NOTIFIER-CONSUMER-041).
 *
 * <p>계약 원천은 analyzer {@code inference/publish.py}의 {@code SIGNAL_STREAM_FIELDS} 7필드이며, 전 값이 문자열로
 * 인코딩된다(analyzer {@code decode_responses=True} · collector {@code StringRedisTemplate}와 동일 계약).
 *
 * <p>7필드 중 하나라도 없으면 해석 불가로 판정한다(REQ-021 ②) — 재시도해도 유효해지지 않으므로 재전달 임계를 기다리지 않고 즉시 DLQ로 보낸다. 반대로
 * <b>미지의 추가 필드는 허용</b>한다: analyzer가 필드를 추가했다는 이유만으로 소비가 멈추면 발행자 배포가 소비자를 깨뜨리는 결합이 생긴다.
 *
 * <p>{@code horizon}은 문자열 그대로 보존한다 — 하류가 {@code signal_price_bands} 조회 키로 무변환 사용하기 때문이다.
 *
 * <p>@MX:ANCHOR: [AUTO] 신호 페이로드 7필드 계약의 단일 해석 지점. 필드 누락 판정(→ DLQ 즉시 이관)과 {@code horizon} 무변환 보존이 모두
 * 여기에서만 일어난다.
 *
 * <p>@MX:REASON: fan_in >= 3 — {@code parse()} 호출 클래스 3개(StreamConsumerWorker 소비 경로,
 * SignalPayloadParserTest, StreamConsumerAutoConfigurationTest). 운영 코드의 호출 클래스는
 * StreamConsumerWorker 하나이므로 3은 테스트 호출을 포함한 값이다.
 */
@Component
public class SignalPayloadParser {

    /** 엔트리 필드 이름 — DB {@code stocks.symbol} 표기. */
    public static final String FIELD_SYMBOL = "symbol";

    /** 엔트리 필드 이름 — {@code D20}/{@code D60} 라벨. */
    public static final String FIELD_HORIZON = "horizon";

    /** 엔트리 필드 이름 — 거래일 (ISO 8601). */
    public static final String FIELD_TRADE_DATE = "trade_date";

    /** 엔트리 필드 이름 — analyzer가 발급한 추적 ID. */
    public static final String FIELD_TRACE_ID = "trace_id";

    /** 엔트리 필드 이름 — 5클래스 등급. */
    public static final String FIELD_SIGNAL_CLASS = "signal_class";

    /** 엔트리 필드 이름 — 앙상블 기대수익률. */
    public static final String FIELD_SCORE = "score";

    /** 엔트리 필드 이름 — 신뢰도. */
    public static final String FIELD_CONFIDENCE = "confidence";

    /**
     * 신호 엔트리 1건을 관측치로 변환한다.
     *
     * @param entry Redis 엔트리 필드 맵
     * @return 신호 관측치
     * @throws PayloadUnparseableException 7필드 중 누락이 있거나 값 형식이 어긋난 경우 (REQ-021 ②)
     */
    public SignalObservation parse(Map<String, String> entry) {
        return new SignalObservation(
                required(entry, FIELD_SYMBOL),
                required(entry, FIELD_HORIZON),
                parseDate(required(entry, FIELD_TRADE_DATE)),
                required(entry, FIELD_TRACE_ID),
                required(entry, FIELD_SIGNAL_CLASS),
                parseDecimal(required(entry, FIELD_SCORE), FIELD_SCORE),
                parseDecimal(required(entry, FIELD_CONFIDENCE), FIELD_CONFIDENCE));
    }

    private static String required(Map<String, String> entry, String field) {
        String value = entry.get(field);
        if (value == null) {
            throw new PayloadUnparseableException("신호 엔트리 필수 필드가 없다: " + field);
        }
        return value;
    }

    private static LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new PayloadUnparseableException(
                    "%s를 ISO 8601 날짜로 읽을 수 없다: %s".formatted(FIELD_TRADE_DATE, text), e);
        }
    }

    private static BigDecimal parseDecimal(String text, String field) {
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new PayloadUnparseableException("%s를 숫자로 읽을 수 없다: %s".formatted(field, text), e);
        }
    }
}
