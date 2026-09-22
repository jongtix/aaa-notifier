package com.aaa.notifier.stream;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@code stream:tick:*} 엔트리를 관측치로 변환하는 파서 (REQ-NOTIFIER-CONSUMER-031/032/033/034).
 *
 * <p>설계 초안 [G-4]가 지적한 "틱 필드 명세 부재"를 해소하는 계약 구현부다. 계약은 추론이 아니라 실물 대조로 확정했다 — 발행 코드(collector {@code
 * KisTickPublisher})의 4필드 엔트리 + 실측 명세({@code api-specs/kis/ws-01}~{@code ws-04}, 2026-06-23/24).
 *
 * <p><b>배치 분할이 이 클래스의 존재 이유 절반이다.</b> KIS Type A 프레임의 배치 건수({@code count})는 발행 시점에 버려지므로(엔트리에 없다),
 * 소비자가 {@code 총 필드 수 ÷ trId별 레코드 필드 수}로 재유도해야 한다. 재유도하지 않고 {@code data}를 1건으로 취급하면 국내 체결 배치(실측
 * 7건/메시지)의 6건이 조용히 유실된다.
 *
 * <p>@MX:ANCHOR: [AUTO] 틱 페이로드 계약의 단일 해석 지점. 배치 분할·심볼 정규화·ZDIV 검증이 모두 여기에서만 일어난다.
 *
 * <p>@MX:REASON: fan_in >= 3 (StreamConsumerWorker 소비 경로, TickPayloadParserTest,
 * TickPayloadParserZdivFallbackTest).
 */
@Slf4j
@Component
public class TickPayloadParser {

    /** 엔트리 필드 이름 — collector {@code KisTickPublisher}가 싣는 정확히 4개 (REQ-031). */
    public static final String FIELD_SYMBOL = "symbol";

    /** 엔트리 필드 이름 — KIS 실시간 트랜잭션 ID. */
    public static final String FIELD_TR_ID = "trId";

    /** 엔트리 필드 이름 — {@code ^} 구분 원시 페이로드. */
    public static final String FIELD_DATA = "data";

    /** 엔트리 필드 이름 — collector가 발급한 추적 ID. */
    public static final String FIELD_TRACE_ID = "trace_id";

    /**
     * 레코드 필드 구분자. {@code split}의 limit은 반드시 {@code -1}로 준다 — 기본값은 후행 빈 필드를 버리므로 레코드가 빈 값으로 끝나는
     * 프레임에서 필드 수가 줄어 배치 분할이 오판된다(국내 {@code [44]} {@code MRKT_WARN_CLS_CODE}는 실측에서 빈 값이다).
     */
    private static final String FIELD_SEPARATOR = "\\^";

    private static final int SPLIT_KEEP_TRAILING_EMPTY = -1;

    /** 체결 시각 형식 — 국내 {@code STCK_CNTG_HOUR}·해외 {@code KHMS} 공통 (HHMMSS, KST). */
    private static final DateTimeFormatter TRADE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HHmmss", Locale.ROOT);

    /**
     * {@code ZDIV} 기대 범위 상한. 시스템 전역 가격 표현 정밀도({@code daily_ohlcv} {@code DECIMAL(18,4)})와 collector
     * {@code OverseasDailyOhlcvCollectionService.ZDIV_MAX} 선례에서 온 값이다(spec.md HISTORY [D-C4]).
     */
    private static final int MAX_PRICE_SCALE = 4;

    private static final int MIN_PRICE_SCALE = 0;

    /**
     * 엔트리 1건을 관측치 목록으로 변환한다.
     *
     * @param stream 소비 중인 스트림 (시장 구분의 상위 권위)
     * @param entry Redis 엔트리 필드 맵
     * @return 배치 건수만큼의 관측치 (체결 또는 호가 — 한 엔트리는 한 종류다)
     * @throws PayloadUnparseableException 구조적으로 해석 불가한 경우 (REQ-021 ②)
     */
    public List<TickObservation> parse(ConsumedStream stream, Map<String, String> entry) {
        String rawTrKey = required(entry, FIELD_SYMBOL);
        String transactionId = required(entry, FIELD_TR_ID);
        String data = required(entry, FIELD_DATA);
        String traceId = entry.get(FIELD_TRACE_ID);

        TickTransactionId trId = TickTransactionId.of(transactionId);
        if (trId.getMarket() != stream.getMarket()) {
            throw new PayloadUnparseableException(
                    "trId 시장이 스트림 시장과 어긋난다: trId=%s(%s), stream=%s(%s)"
                            .formatted(
                                    transactionId,
                                    trId.getMarket(),
                                    stream.getKey(),
                                    stream.getMarket()));
        }

        List<String[]> records = splitIntoRecords(data, trId);
        List<TickObservation> observations = new ArrayList<>(records.size());
        for (String[] fields : records) {
            observations.add(toObservation(stream, trId, fields, rawTrKey, traceId));
        }
        return observations;
    }

    /** {@code 총 필드 수 ÷ 레코드당 필드 수}로 배치 건수를 재유도해 레코드 단위로 쪼갠다 (REQ-032). */
    private List<String[]> splitIntoRecords(String data, TickTransactionId trId) {
        if (data.isEmpty()) {
            throw new PayloadUnparseableException("data가 비어 있다: trId=" + trId);
        }

        String[] fields = data.split(FIELD_SEPARATOR, SPLIT_KEEP_TRAILING_EMPTY);
        int perRecord = trId.getFieldsPerRecord();
        if (fields.length % perRecord != 0) {
            throw new PayloadUnparseableException(
                    "총 필드 수가 레코드당 필드 수로 나누어떨어지지 않는다: trId=%s, total=%d, perRecord=%d"
                            .formatted(trId, fields.length, perRecord));
        }

        int batchCount = fields.length / perRecord;
        List<String[]> records = new ArrayList<>(batchCount);
        for (int i = 0; i < batchCount; i++) {
            records.add(Arrays.copyOfRange(fields, i * perRecord, (i + 1) * perRecord));
        }
        return records;
    }

    private TickObservation toObservation(
            ConsumedStream stream,
            TickTransactionId trId,
            String[] fields,
            String rawTrKey,
            String traceId) {
        TradeFieldLayout layout = TradeFieldLayout.of(stream.getMarket());
        String symbol = fields[layout.symbolIndex()];
        if (symbol.isEmpty()) {
            throw new PayloadUnparseableException("정규 종목 심볼이 비어 있다: trId=" + trId);
        }

        if (trId.getKind() == TickTransactionId.Kind.QUOTE) {
            return new QuoteObservation(
                    symbol,
                    rawTrKey,
                    stream.getMarket(),
                    trId.name(),
                    String.join("^", fields),
                    traceId);
        }
        return toTrade(stream, fields, layout, symbol, rawTrKey, traceId);
    }

    private TradeTickObservation toTrade(
            ConsumedStream stream,
            String[] fields,
            TradeFieldLayout layout,
            String symbol,
            String rawTrKey,
            String traceId) {
        String priceText = fields[layout.priceIndex()];
        BigDecimal price = parseDecimal(priceText, "체결가");
        long volume = parseLong(fields[layout.volumeIndex()], "체결량");
        long accumulatedVolume = parseLong(fields[layout.accumulatedVolumeIndex()], "누적 거래량");
        LocalTime tradeTime = parseTime(fields[layout.tradeTimeIndex()]);

        Integer priceScale = null;
        if (layout.priceScaleIndex() != null) {
            priceScale =
                    resolvePriceScale(
                            fields[layout.priceScaleIndex()], price, priceText, rawTrKey, traceId);
        }

        return new TradeTickObservation(
                symbol,
                rawTrKey,
                stream.getMarket(),
                price,
                volume,
                accumulatedVolume,
                tradeTime,
                priceScale,
                traceId);
    }

    /**
     * 해외 체결의 {@code ZDIV}를 판독해 체결가 자리수와 대조한다 (REQ-034).
     *
     * <p><b>배율 연산을 하지 않는다.</b> {@code ZDIV}는 "가격 필드 소수점 자리수"이지 원시 정수를 소수로 환산하는 배율 지수가 아니다 — {@code
     * LAST}는 이미 그 자리수로 소수점이 찍힌 상태로 도착하므로({@code 298.4700}, {@code ZDIV=4}) {@code 10^-ZDIV}를 곱하면
     * {@code 0.02984700}으로 오염된다(spec.md HISTORY [D-C4]).
     *
     * <p>3조건(판독 실패 / 범위 이탈 / 자리수 불일치) 중 무엇에 걸려도 WARN을 남기고 <b>원값 그대로 전달을 계속</b>한다. 해석 불가(DLQ)로 승격하지
     * 않는 이유는 조건마다 다르다 — 판독 실패·범위 이탈은 이상이 메타데이터 쪽이라 실제 체결 데이터를 폐기하는 손실이 더 크고, 자리수 불일치는 필드 수·분할·타입
     * 변환이 전부 정상이라 DLQ로 보내면 이관 사유가 사실과 달라진다. 어느 쪽이 참값인지 판정할 근거가 소비 계층에는 없으므로 추측 보정도 하지 않는다.
     *
     * @return 판독된 {@code ZDIV}(범위를 벗어나도 원값 보존), 정수 판독 실패 시 {@code null}
     */
    private Integer resolvePriceScale(
            String rawPriceScale,
            BigDecimal price,
            String rawPrice,
            String rawTrKey,
            String traceId) {
        Integer priceScale;
        try {
            priceScale = Integer.valueOf(rawPriceScale.trim());
        } catch (NumberFormatException e) {
            warnPriceScale("ZDIV를 정수로 판독할 수 없다", rawPriceScale, rawPrice, rawTrKey, traceId);
            return null;
        }

        if (priceScale < MIN_PRICE_SCALE || priceScale > MAX_PRICE_SCALE) {
            warnPriceScale(
                    "ZDIV가 기대 범위(%d~%d)를 벗어난다".formatted(MIN_PRICE_SCALE, MAX_PRICE_SCALE),
                    rawPriceScale,
                    rawPrice,
                    rawTrKey,
                    traceId);
        } else if (price.scale() != priceScale) {
            warnPriceScale(
                    "체결가 소수 자리수(%d)가 ZDIV와 불일치한다".formatted(price.scale()),
                    rawPriceScale,
                    rawPrice,
                    rawTrKey,
                    traceId);
        }
        return priceScale;
    }

    private void warnPriceScale(
            String reason, String rawPriceScale, String rawPrice, String rawTrKey, String traceId) {
        log.warn(
                "[tick-parser] {} — 값을 보정하지 않고 원문 그대로 전달한다 trace_id={} tr_key={} zdiv_raw={} last_raw={}",
                reason,
                traceId,
                rawTrKey,
                rawPriceScale,
                rawPrice);
    }

    private static String required(Map<String, String> entry, String field) {
        String value = entry.get(field);
        if (value == null) {
            throw new PayloadUnparseableException("엔트리 필수 필드가 없다: " + field);
        }
        return value;
    }

    private static BigDecimal parseDecimal(String text, String label) {
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new PayloadUnparseableException("%s를 숫자로 읽을 수 없다: %s".formatted(label, text), e);
        }
    }

    private static long parseLong(String text, String label) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new PayloadUnparseableException("%s를 정수로 읽을 수 없다: %s".formatted(label, text), e);
        }
    }

    private static LocalTime parseTime(String text) {
        try {
            return LocalTime.parse(text, TRADE_TIME_FORMAT);
        } catch (DateTimeParseException e) {
            throw new PayloadUnparseableException("체결 시각을 HHMMSS로 읽을 수 없다: " + text, e);
        }
    }

    /**
     * 체결 레코드의 필드 위치 (REQ-033 인덱스 표, 0-기준).
     *
     * <p>호가 레코드(62/71필드)의 레벨별 호가·잔량 인덱스는 <b>의도적으로 없다</b> — 소비 규칙이 정의되지 않았으므로 패스스루로만 다룬다. 여기 있는
     * {@code symbolIndex}는 체결·호가가 공유하는 레코드 선두 위치이지 호가 고유 필드가 아니다.
     */
    private record TradeFieldLayout(
            int symbolIndex,
            int tradeTimeIndex,
            int priceIndex,
            int volumeIndex,
            int accumulatedVolumeIndex,
            Integer priceScaleIndex) {

        /** 국내 {@code H0STCNT0} — {@code ZDIV} 필드가 존재하지 않는다. */
        private static final TradeFieldLayout DOMESTIC =
                new TradeFieldLayout(0, 1, 2, 12, 13, null);

        /**
         * 해외 {@code HDFSCNT0} — 정규 심볼은 {@code [1]} {@code SYMB}, 체결 시각은 {@code [7]} {@code
         * KHMS}(KST).
         */
        private static final TradeFieldLayout OVERSEAS = new TradeFieldLayout(1, 7, 11, 19, 20, 2);

        static TradeFieldLayout of(Market market) {
            return market == Market.DOMESTIC ? DOMESTIC : OVERSEAS;
        }
    }

    /** KIS 실시간 트랜잭션 ID별 레코드 계약 (REQ-032 표, {@code api-specs/kis/ws-01}~{@code ws-04} 실측). */
    @Getter
    private enum TickTransactionId {
        /**
         * 국내 체결 — 47필드.
         *
         * <p><b>[2026-09-22 정정]</b> 원안(2026-06-23 api-specs 실측)은 46필드였으나, 프로덕션 DLQ 원본 페이로드 재대조 결과
         * KIS가 문서화된 46필드({@code MKSC_SHRN_ISCD}~{@code ABCD_MNPRC}) 뒤에 미확인 트레일링 필드 1개를 추가로
         * 보낸다(index 46, 실측 전부 {@code 2}) — 이 정정 전에는 국내 체결 트래픽 전량이 배치 분할 나눗셈에서 실패해 DLQ로 이관되고 있었다.
         * {@code TradeFieldLayout.DOMESTIC}은 index 0~13만 참조하므로 필드 인덱스 매핑 자체는 영향받지 않는다 — 깨진 것은 이 나눗셈
         * 상수뿐이다.
         */
        H0STCNT0(47, Kind.TRADE, Market.DOMESTIC),

        /**
         * 국내 호가 — 63필드, 패스스루.
         *
         * <p><b>[2026-09-22 정정]</b> {@code H0STCNT0}와 동일 사유로 62→63 정정 (실측: 프로덕션 DLQ {@code
         * payload_unparseable} 로그 {@code total=63, perRecord=62}). 패스스루라 신규 필드의 이름·의미는 불필요.
         */
        H0STASP0(63, Kind.QUOTE, Market.DOMESTIC),

        /** 해외 체결 — 26필드. */
        HDFSCNT0(26, Kind.TRADE, Market.OVERSEAS),

        /** 해외 호가 — 71필드, 패스스루. */
        HDFSASP0(71, Kind.QUOTE, Market.OVERSEAS);

        /** 레코드 1건의 필드 수. */
        private final int fieldsPerRecord;

        /** 체결/호가 구분 — 호가는 필드 해석 없이 패스스루한다. */
        private final Kind kind;

        /** 이 trId가 속한 시장 (스트림 시장과 대조해 오라우팅을 잡는다). */
        private final Market market;

        TickTransactionId(int fieldsPerRecord, Kind kind, Market market) {
            this.fieldsPerRecord = fieldsPerRecord;
            this.kind = kind;
            this.market = market;
        }

        enum Kind {
            TRADE,
            QUOTE
        }

        static TickTransactionId of(String transactionId) {
            for (TickTransactionId candidate : values()) {
                if (candidate.name().equals(transactionId)) {
                    return candidate;
                }
            }
            throw new PayloadUnparseableException("미지의 trId다: " + transactionId);
        }
    }
}
