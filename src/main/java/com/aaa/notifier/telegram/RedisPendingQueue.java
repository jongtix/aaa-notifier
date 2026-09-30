package com.aaa.notifier.telegram;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 대기 큐의 Redis List 구현 (TECHSPEC §1.3, plan.md §D.7) — 항목당 JSON 문자열 1개를 뒤로 넣고({@code RPUSH}), 앞에서
 * 읽어({@code LRANGE}) 요약 후 읽은 개수만큼 잘라낸다({@code LTRIM}).
 *
 * <p>키에는 보존 기간을 백스톱 TTL로 걸고 적재 때마다 갱신한다 — 요약이 오래 일어나지 않아도 큐가 무한히 남지 않는다. 항목별 보존 기간 판정은 요약 시점에
 * {@code queued_at}으로 한다.
 */
@Slf4j
@RequiredArgsConstructor
public class RedisPendingQueue implements PendingQueue {

    /** TECHSPEC §1.3 대기 큐 키. */
    static final String KEY = "queue:telegram:pending";

    private final StringRedisTemplate redis;
    private final Duration retention;
    private final ObjectMapper objectMapper =
            JsonMapper.builder()
                    .addModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .build();

    @Override
    public void append(QueuedAlert alert) {
        String json;
        try {
            json = objectMapper.writeValueAsString(alert);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("대기 큐 항목 직렬화 실패 trace_id=" + alert.traceId(), e);
        }
        redis.opsForList().rightPush(KEY, json);
        redis.expire(KEY, retention);
    }

    @Override
    public QueueSnapshot snapshot() {
        List<String> raw = redis.opsForList().range(KEY, 0, -1);
        if (raw == null || raw.isEmpty()) {
            return new QueueSnapshot(List.of(), 0);
        }
        List<QueuedAlert> alerts = new ArrayList<>(raw.size());
        for (String entry : raw) {
            try {
                alerts.add(objectMapper.readValue(entry, QueuedAlert.class));
            } catch (JsonProcessingException e) {
                log.warn(
                        "[telegram-queue] 해석할 수 없는 대기 큐 항목 — 건수로만 센다 error={}",
                        e.getOriginalMessage());
            }
        }
        return new QueueSnapshot(alerts, raw.size());
    }

    @Override
    public void removeFirst(int count) {
        if (count > 0) {
            redis.opsForList().trim(KEY, count, -1);
        }
    }

    @Override
    public long size() {
        Long size = redis.opsForList().size(KEY);
        return size == null ? 0 : size;
    }
}
