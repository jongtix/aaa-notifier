package com.aaa.notifier.stream;

import java.util.Map;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 통합 테스트 공용 Redis 스트림 조작 헬퍼. */
final class StreamTestSupport {

    private StreamTestSupport() {}

    /** 스트림에 엔트리를 발행하고 부여된 메시지 ID를 돌려준다. */
    static String publish(
            StringRedisTemplate redisTemplate, ConsumedStream stream, Map<String, String> fields) {
        RecordId recordId =
                redisTemplate.opsForStream().add(MapRecord.create(stream.getKey(), fields));
        return recordId == null ? "" : recordId.getValue();
    }

    /** 그룹 전체의 미확인 메시지 건수. */
    static long pendingCount(StringRedisTemplate redisTemplate, ConsumedStream stream) {
        PendingMessagesSummary summary =
                redisTemplate
                        .opsForStream()
                        .pending(stream.getKey(), ConsumedStream.CONSUMER_GROUP);
        return summary == null ? 0L : summary.getTotalPendingMessages();
    }

    /** DLQ 스트림 길이. */
    static long dlqSize(StringRedisTemplate redisTemplate, ConsumedStream stream) {
        Long size = redisTemplate.opsForStream().size(stream.dlqKey());
        return size == null ? 0L : size;
    }
}
