package com.aaa.notifier.stream;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 소비 단위 생명주기 — 그룹 초기화 + 스트림당 가상 스레드 1개 (REQ-NOTIFIER-CONSUMER-001/002/003).
 *
 * <p>4개 스트림이 각각 독립된 소비 단위로 동작하며, 한 스트림의 소비 실패가 나머지 3개를 중단시키지 않는다 — 워커가 스레드 단위로 분리되어 있고 각자 자기 루프에서
 * 오류를 흡수하기 때문이다.
 *
 * <p><b>{@code SmartLifecycle}의 기본 phase({@code Integer.MAX_VALUE})를 그대로 쓴다.</b> 높은 phase가 먼저 멈추므로
 * Redis 연결이 닫히기 전에 소비가 멈춘다 — 반대 순서면 종료 중에 연결 오류가 쏟아진다.
 *
 * <p>@MX:ANCHOR: [AUTO] 소비 계층의 유일한 기동/종료 진입점. 그룹 생성·스레드 기동·종료 대기가 전부 여기를 통과한다.
 *
 * <p>@MX:REASON: fan_in >= 3 (StreamConsumerAutoConfiguration 빈 등록, Spring 생명주기 start/stop,
 * StreamConsumerIntegrationTest 재기동 검증).
 */
@Slf4j
public class StreamConsumerRunner implements SmartLifecycle {

    /**
     * 종료 시 워커 스레드 정리를 기다리는 상한. {@code spring.lifecycle.timeout-per-shutdown-phase}(30s)보다 짧아야 한다.
     */
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);

    /** 원인 사슬 탐색 깊이 상한 — 자기 참조 사슬에 갇히지 않기 위한 방어값이다. */
    private static final int MAX_CAUSE_DEPTH = 16;

    /** 가변 템플릿 참조 대신 무상태 연산 뷰만 보관한다 ({@link StreamConsumerWorker}와 같은 이유). */
    private final StreamOperations<String, String, String> streamOperations;

    private final List<StreamConsumerWorker> workers;
    private final AtomicBoolean running = new AtomicBoolean();

    private ExecutorService executor;

    public StreamConsumerRunner(
            StringRedisTemplate redisTemplate, List<StreamConsumerWorker> workers) {
        this.streamOperations = redisTemplate.opsForStream();
        this.workers = List.copyOf(workers);
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        for (ConsumedStream stream : ConsumedStream.values()) {
            ensureGroup(stream);
        }
        executor = Executors.newVirtualThreadPerTaskExecutor();
        workers.forEach(executor::submit);
        log.info("[stream-consumer] 소비 단위 {}개 기동 완료", workers.size());
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        workers.forEach(StreamConsumerWorker::stop);
        if (executor == null) {
            return;
        }
        // 진행 중인 블로킹 읽기를 인터럽트로 깨운다 — blockTimeout 만료를 기다리면 종료가 느려진다.
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("[stream-consumer] 소비 스레드가 {} 안에 정리되지 않았다", SHUTDOWN_WAIT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("[stream-consumer] 소비 단위 종료 완료");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 그룹을 스트림 말미({@code $}) 기준 + 스트림 자동 생성으로 만든다.
     *
     * <p>{@code $}는 그룹 생성 시점 이후의 메시지부터 받겠다는 뜻이다. 재기동 시에는 그룹이 이미 있어 {@code BUSYGROUP}으로 생성이 무시되므로
     * 마지막 확인응답 지점부터 이어서 처리한다 — <b>재기동이 오프셋을 되감지 않는다</b>(REQ-002).
     *
     * <p>스트림 자동 생성 덕에 소비자가 발행자보다 먼저 기동해도 정상 동작하며, 그래서 compose 기동 순서를 바꿀 필요가 없다.
     *
     * <p>한 스트림의 생성 실패로 나머지 3개를 포기하지 않는다(REQ-003). 실패한 스트림은 워커의 백오프 루프가 계속 재시도하므로, 일시적 두절이면 스스로 회복하고
     * 아니면 로그로 드러난다.
     */
    private void ensureGroup(ConsumedStream stream) {
        try {
            streamOperations.createGroup(
                    stream.getKey(), ReadOffset.latest(), ConsumedStream.CONSUMER_GROUP);
            log.info(
                    "[stream-consumer] 그룹 생성 stream={} group={}",
                    stream.getKey(),
                    ConsumedStream.CONSUMER_GROUP);
        } catch (DataAccessException e) {
            if (isGroupAlreadyExists(e)) {
                if (log.isDebugEnabled()) {
                    log.debug("[stream-consumer] 그룹이 이미 있다 — 생성을 무시한다 stream={}", stream.getKey());
                }
                return;
            }
            log.error("[stream-consumer] 그룹 생성 실패 — 이 스트림만 소비가 지연된다 stream={}", stream.getKey(), e);
        }
    }

    /**
     * Redis의 {@code BUSYGROUP} 응답은 "이미 있음"이라는 정상 신호다.
     *
     * <p>Spring이 번역한 예외의 원인 사슬 어딘가에 실리므로 끝까지 훑되, 자기 참조 사슬에 갇히지 않도록 깊이를 제한한다.
     */
    private static boolean isGroupAlreadyExists(Throwable error) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (cause.getMessage() != null && cause.getMessage().contains("BUSYGROUP")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
