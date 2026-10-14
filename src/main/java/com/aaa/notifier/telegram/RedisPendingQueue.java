package com.aaa.notifier.telegram;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

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
    // Jackson 3 기본값(알파벳 정렬·원시 타입 null 실패·모르는 필드 허용 등)이 아니라 Jackson 2 기본값으로 만들어 Boot 3.5 시절과 같은
    // 형식을 쓰고 읽는다 — 배포·롤백 시 큐에 남은 항목을 양쪽 버전이 서로 읽어야 한다(SPEC-INFRA-BOOT4-001 REQ-BOOT4-011·016).
    // java.time 지원은 Jackson 3에 내장이라 모듈 등록이 필요 없다.
    private final ObjectMapper objectMapper =
            JsonMapper.builderWithJackson2Defaults()
                    .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .build();

    @Override
    public void append(QueuedAlert alert) {
        String json;
        try {
            json = objectMapper.writeValueAsString(alert);
        } catch (JacksonException e) {
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
            } catch (JacksonException e) {
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
