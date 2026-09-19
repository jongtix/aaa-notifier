package com.aaa.notifier.stream;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * KIS 실시간 프레임 실측 픽스처 (plan.md §D M1 — 계약 고정).
 *
 * <p>아래 레코드 원문은 {@code api-specs/kis/ws-01-국내주식실시간체결.md}(2026-06-23 실측)와 {@code
 * ws-03-해외주식실시간체결.md}(2026-06-24 실측)의 "실제 수신 예시" 절에서 그대로 옮긴 것이다. 필드 수(46/26)와 본 SPEC이 소비하는 인덱스 위치는
 * {@code TickPayloadParserTest}가 직접 단언해 고정한다.
 *
 * <p>국내 레코드의 {@code [44]} {@code MRKT_WARN_CLS_CODE}는 실측에서 <b>빈 값</b>이다(엣지케이스 E1). {@code
 * String.split("\\^")}를 limit 없이 쓰면 후행 빈 필드가 버려져 배치 분할이 오판되므로, 이 픽스처가 그 회귀를 잡는다.
 *
 * <p>배치 프레임은 실측 원문이 1번째 레코드만 보여 주므로(이후는 {@code ^005930^...}로 생략) 여기서 조립한다. 조립 시 체결가·체결량을 레코드마다 다르게
 * 바꾸는 이유는 AC-12 단언 ②("첫 레코드 값의 7회 반복이 아님")가 기계적으로 성립하게 하기 위함이다.
 */
final class KisFrameFixtures {

    /** 국내 체결 `H0STCNT0` 레코드 1건 (46필드, ws-01 실측 원문). */
    static final String DOMESTIC_TRADE_RECORD =
            "005930^151425^313000^5^-40500^-11.46^335674.46^347500^353000^310000^313500^313000"
                    + "^825^34502265^11581539743500^382205^358363^-23842^67.05^19740248^13236777"
                    + "^5^0.39^141.81^090008^5^-34500^090010^5^-40000^151413^2^3000^20260623^20^N"
                    + "^2751^7557^92085^151119^0.59^21647427^159.38^0^^322000";

    /** 해외 체결 `HDFSCNT0` 레코드 1건 (26필드, ws-03 실측 원문). */
    static final String OVERSEAS_TRADE_RECORD =
            "DNASAAPL^AAPL^4^20260623^20260623^113004^20260624^003004^297.5380^301.6400^295.1800"
                    + "^298.4700^2^1.4600^+0.49^298.4700^298.5100^43^45^218^8998906^2690866999"
                    + "^1580920^2118263^133.99^2";

    /** 국내 체결 레코드의 필드 수 (REQ-032 표). */
    static final int DOMESTIC_TRADE_FIELD_COUNT = 46;

    /** 해외 체결 레코드의 필드 수 (REQ-032 표). */
    static final int OVERSEAS_TRADE_FIELD_COUNT = 26;

    /** 국내 호가 레코드의 필드 수 (REQ-032 표, ws-02 실측). */
    static final int DOMESTIC_QUOTE_FIELD_COUNT = 62;

    /** 해외 호가 레코드의 필드 수 (REQ-032 표, ws-04 실측). */
    static final int OVERSEAS_QUOTE_FIELD_COUNT = 71;

    static final String TRACE_ID = "3f2a1b4c-5d6e-4f70-8192-a3b4c5d6e7f8";

    private KisFrameFixtures() {}

    /** 레코드를 `^`로 분할한다(후행 빈 필드 보존 — 프로덕션 파서와 동일 규칙). */
    static String[] split(String record) {
        return record.split("\\^", -1);
    }

    /** 레코드의 지정 인덱스 값을 교체한 새 레코드를 만든다. */
    static String withField(String record, int index, String value) {
        String[] parts = split(record);
        parts[index] = value;
        return String.join("^", parts);
    }

    /**
     * 같은 레코드를 {@code count}건 이어 붙이되 체결가·체결량을 건마다 다르게 바꾼 배치 `data`를 만든다.
     *
     * @param record 기준 레코드
     * @param priceIndex 체결가 인덱스
     * @param volumeIndex 체결량 인덱스
     * @param basePrice 0번째 레코드의 체결가 (i번째는 {@code basePrice + i})
     * @param baseVolume 0번째 레코드의 체결량 (i번째는 {@code baseVolume + i})
     * @param count 배치 건수
     */
    static String batch(
            String record,
            int priceIndex,
            int volumeIndex,
            long basePrice,
            long baseVolume,
            int count) {
        return IntStream.range(0, count)
                .mapToObj(
                        i -> {
                            String withPrice =
                                    withField(record, priceIndex, String.valueOf(basePrice + i));
                            return withField(
                                    withPrice, volumeIndex, String.valueOf(baseVolume + i));
                        })
                .collect(Collectors.joining("^"));
    }

    /** {@code fieldCount}개 필드로 구성된 합성 레코드를 만든다(호가 패스스루 테스트용). */
    static String syntheticRecord(String symbol, int fieldCount) {
        StringBuilder builder = new StringBuilder(symbol);
        for (int i = 1; i < fieldCount; i++) {
            builder.append('^').append(i);
        }
        return builder.toString();
    }

    /** 해외 합성 레코드 — {@code [0]}=tr_key, {@code [1]}=정규 심볼. */
    static String syntheticOverseasRecord(String trKey, String symbol, int fieldCount) {
        StringBuilder builder = new StringBuilder(trKey).append('^').append(symbol);
        for (int i = 2; i < fieldCount; i++) {
            builder.append('^').append(i);
        }
        return builder.toString();
    }

    /** 틱 엔트리 4필드 맵을 만든다 (REQ-031). */
    static Map<String, String> tickEntry(String symbol, String trId, String data, String traceId) {
        return Map.of("symbol", symbol, "trId", trId, "data", data, "trace_id", traceId);
    }

    /** 신호 엔트리 7필드 맵을 만든다 (REQ-041, analyzer `SIGNAL_STREAM_FIELDS`). */
    static Map<String, String> signalEntry() {
        return Map.of(
                "symbol", "AAPL",
                "horizon", "D20",
                "trade_date", "2026-09-18",
                "trace_id", TRACE_ID,
                "signal_class", "STRONG_BUY",
                "score", "0.043",
                "confidence", "0.71");
    }

    /** 엔트리에서 키 1개를 제거한 맵을 만든다 (필수 필드 누락 케이스). */
    static Map<String, String> without(Map<String, String> entry, String key) {
        return entry.entrySet().stream()
                .filter(e -> !e.getKey().equals(key))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** 엔트리의 키 1개를 다른 값으로 교체한 맵을 만든다 (값 형식 오류 케이스). */
    static Map<String, String> replace(Map<String, String> entry, String key, String value) {
        return Stream.concat(
                        without(entry, key).entrySet().stream(),
                        Map.of(key, value).entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
