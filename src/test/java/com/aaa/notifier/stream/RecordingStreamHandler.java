package com.aaa.notifier.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 통합 테스트용 하류 핸들러 — 하류 도달을 기록하고, 특정 신호에만 실패를 주입하며, 전달을 수행한 스레드를 보관한다.
 *
 * <p>미확인 건수처럼 <b>일시적인 상태를 폴링하지 않고</b> 이 핸들러가 직접 센 호출 횟수·전달 스레드를 단언 근거로 쓴다 — 전자는 확인응답·이관·재소유 타이밍에 따라
 * 관측 창이 사라질 수 있지만 후자는 사라지지 않는다.
 */
final class RecordingStreamHandler implements StreamObservationHandler {

    private final List<TradeTickObservation> tradeLog = new CopyOnWriteArrayList<>();
    private final List<QuoteObservation> quoteLog = new CopyOnWriteArrayList<>();
    private final List<SignalObservation> signalLog = new CopyOnWriteArrayList<>();
    private final Set<Thread> deliveryThreadSet = ConcurrentHashMap.newKeySet();
    private final AtomicInteger signalAttemptCount = new AtomicInteger();
    private final AtomicReference<String> failingSignalSymbol = new AtomicReference<>();
    private final AtomicReference<String> failOnceSignalSymbol = new AtomicReference<>();
    private final AtomicBoolean failOnceConsumed = new AtomicBoolean();

    @Override
    public void onTradeTick(TradeTickObservation observation) {
        deliveryThreadSet.add(Thread.currentThread());
        tradeLog.add(observation);
    }

    @Override
    public void onQuote(QuoteObservation observation) {
        deliveryThreadSet.add(Thread.currentThread());
        quoteLog.add(observation);
    }

    @Override
    public void onSignal(SignalObservation observation) {
        deliveryThreadSet.add(Thread.currentThread());
        signalAttemptCount.incrementAndGet();
        if (observation.symbol().equals(failingSignalSymbol.get())) {
            throw new IllegalStateException("주입된 하류 실패 symbol=" + observation.symbol());
        }
        if (observation.symbol().equals(failOnceSignalSymbol.get())
                && !failOnceConsumed.getAndSet(true)) {
            throw new IllegalStateException("주입된 1회성 하류 실패 symbol=" + observation.symbol());
        }
        signalLog.add(observation);
    }

    /** 정상 도달한 체결 관측치의 스냅숏. */
    List<TradeTickObservation> trades() {
        return new ArrayList<>(tradeLog);
    }

    /** 정상 도달한 신호 관측치의 스냅숏 (실패를 주입당한 호출은 포함하지 않는다). */
    List<SignalObservation> signals() {
        return new ArrayList<>(signalLog);
    }

    /** 신호 핸들러가 호출된 횟수 — 실패를 던진 호출도 센다. */
    int signalAttempts() {
        return signalAttemptCount.get();
    }

    /** 지금까지 하류 전달을 수행한 서로 다른 스레드들 — 소비 단위(스트림당 1스레드)의 생존 확인에 쓴다. */
    List<Thread> deliveryThreads() {
        return new ArrayList<>(deliveryThreadSet);
    }

    /** 해당 심볼의 신호에만 호출마다 예외를 던지게 한다. */
    void failSignalsOf(String symbol) {
        failingSignalSymbol.set(symbol);
    }

    /** 해당 심볼의 신호는 최초 1회 호출에서만 예외를 던지고, 그 이후 호출(재전달)부터는 정상 처리한다(AC-7). */
    void failFirstSignalAttemptOf(String symbol) {
        failOnceSignalSymbol.set(symbol);
        failOnceConsumed.set(false);
    }

    void clear() {
        tradeLog.clear();
        quoteLog.clear();
        signalLog.clear();
        deliveryThreadSet.clear();
        signalAttemptCount.set(0);
        failingSignalSymbol.set(null);
        failOnceSignalSymbol.set(null);
        failOnceConsumed.set(false);
    }
}
