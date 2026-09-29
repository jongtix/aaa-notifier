package com.aaa.notifier.filter;

import com.aaa.notifier.stream.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 종목별 당일 장중 상태 — 고가·저가(ATR 가드 입력, REQ-022)와 마지막 누적 거래량(재전달 멱등).
 *
 * <p><b>재전달 멱등</b>: 소비 계층의 재전달은 엔트리(배치 프레임) 단위라, 한 관측치가 실패하면 같은 엔트리의 앞선 관측치까지 다시 온다 ({@code
 * StreamObservationHandler} 계약). 누적 거래량은 같은 거래일 안에서 체결마다 단조 증가하므로, 이미 본 값 이하의 관측치는 재전달로 보고 건너뛴다 —
 * 확증 카운터가 같은 체결로 두 번 오르지 않게 한다. 누적 거래량이 0으로만 오는 시장에서는 판별할 수 없으므로 건너뛰지 않는다.
 *
 * <p>프로세스 메모리 상태다 — 재시작하면 당일 고저는 재시작 이후 체결로 다시 쌓인다(과소 추정 → ATR 가드가 덜 막는 방향). 같은 종목은 한 스트림(한 소비
 * 스레드)에서만 오므로 종목 단위 갱신 경쟁이 없다.
 */
public class IntradayTracker {

    private final Map<Key, State> states = new ConcurrentHashMap<>();

    /**
     * 체결 1건을 반영한다.
     *
     * @param tradeDate 시장 현지 거래일 — 바뀌면 상태를 새로 시작한다
     * @return 반영 후 당일 고저. 재전달(누적 거래량 비증가)이면 빈 값
     */
    public Optional<Snapshot> record(
            Market market,
            String symbol,
            LocalDate tradeDate,
            BigDecimal price,
            long accumulatedVolume) {
        Key key = new Key(market, symbol);
        State previous = states.get(key);
        if (previous == null || !previous.tradeDate().equals(tradeDate)) {
            State fresh = new State(tradeDate, price, price, accumulatedVolume);
            states.put(key, fresh);
            return Optional.of(fresh.snapshot());
        }
        if (accumulatedVolume > 0 && accumulatedVolume <= previous.lastAccumulatedVolume()) {
            return Optional.empty();
        }
        State next =
                new State(
                        tradeDate,
                        previous.high().max(price),
                        previous.low().min(price),
                        accumulatedVolume);
        states.put(key, next);
        return Optional.of(next.snapshot());
    }

    /**
     * 당일 고저.
     *
     * @param high 당일 고가(관측분)
     * @param low 당일 저가(관측분)
     */
    public record Snapshot(BigDecimal high, BigDecimal low) {

        /** 당일 누적 변동폭(고가 − 저가). */
        public BigDecimal range() {
            return high.subtract(low);
        }
    }

    private record Key(Market market, String symbol) {}

    private record State(
            LocalDate tradeDate, BigDecimal high, BigDecimal low, long lastAccumulatedVolume) {

        Snapshot snapshot() {
            return new Snapshot(high, low);
        }
    }
}
