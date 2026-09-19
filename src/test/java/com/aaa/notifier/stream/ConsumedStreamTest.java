package com.aaa.notifier.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 소비 대상 스트림·Consumer Group·컨슈머 이름 계약 테스트 (REQ-NOTIFIER-CONSUMER-001, AC-1).
 *
 * <p>AC-1 단언 ①~③ 중 이름 규칙(① 그룹명 `notifier`, ② 컨슈머 이름 4종, ③ 컨슈머 이름에 `:` 부재)을 순수 단위 테스트로 고정한다. 실 Redis
 * `XINFO GROUPS` 대조는 통합 테스트(`StreamConsumerLifecycleIntegrationTest`)가 담당한다.
 */
@DisplayName("ConsumedStream — 4스트림 키·그룹·컨슈머 이름 계약 (REQ-001, AC-1)")
class ConsumedStreamTest {

    @Nested
    @DisplayName("스트림 키")
    class StreamKeys {

        @Test
        @DisplayName("소비 대상은 정확히 4개 스트림이다")
        void consumedStreams_areExactlyFour() {
            assertThat(ConsumedStream.values()).hasSize(4);
        }

        @Test
        @DisplayName("4개 스트림 키가 TECHSPEC 5.1 계약과 일치한다")
        void streamKeys_matchContract() {
            List<String> keys =
                    Arrays.stream(ConsumedStream.values()).map(ConsumedStream::key).toList();

            assertThat(keys)
                    .containsExactly(
                            "stream:tick:domestic",
                            "stream:tick:overseas",
                            "stream:signal:domestic",
                            "stream:signal:overseas");
        }

        @Test
        @DisplayName("DLQ 키는 `stream:dlq:` + 원본 스트림명이다")
        void dlqKey_prefixesOriginalStreamName() {
            assertThat(ConsumedStream.TICK_DOMESTIC.dlqKey())
                    .isEqualTo("stream:dlq:stream:tick:domestic");
            assertThat(ConsumedStream.SIGNAL_OVERSEAS.dlqKey())
                    .isEqualTo("stream:dlq:stream:signal:overseas");
        }
    }

    @Nested
    @DisplayName("Consumer Group · 컨슈머 이름 (AC-1 단언 ①~③)")
    class GroupAndConsumerNames {

        @Test
        @DisplayName("① 4스트림 모두 단일 그룹 `notifier`를 사용한다")
        void consumerGroup_isSingleNotifierGroup() {
            assertThat(ConsumedStream.CONSUMER_GROUP).isEqualTo("notifier");
        }

        @Test
        @DisplayName("② 컨슈머 이름은 `{서비스명}-{스트림역할}` 규칙을 따른다")
        void consumerNames_followServiceRoleConvention() {
            List<String> names =
                    Arrays.stream(ConsumedStream.values())
                            .map(ConsumedStream::consumerName)
                            .toList();

            assertThat(names)
                    .containsExactly(
                            "notifier-tick-domestic",
                            "notifier-tick-overseas",
                            "notifier-signal-domestic",
                            "notifier-signal-overseas");
        }

        @Test
        @DisplayName("③ 컨슈머 이름 어디에도 `:` 구분자가 없다 (키 구분자와 의도적 구분)")
        void consumerNames_containNoColon() {
            assertThat(ConsumedStream.values())
                    .allSatisfy(stream -> assertThat(stream.consumerName()).doesNotContain(":"));
        }
    }

    @Nested
    @DisplayName("시장 구분 · 페이로드 종류")
    class MarketAndPayloadKind {

        @Test
        @DisplayName("시장 구분은 스트림 키에서 결정된다 (trId가 아님 — 스트림이 상위 권위)")
        void market_isDerivedFromStreamKey() {
            assertThat(ConsumedStream.TICK_DOMESTIC.market()).isEqualTo(Market.DOMESTIC);
            assertThat(ConsumedStream.TICK_OVERSEAS.market()).isEqualTo(Market.OVERSEAS);
            assertThat(ConsumedStream.SIGNAL_DOMESTIC.market()).isEqualTo(Market.DOMESTIC);
            assertThat(ConsumedStream.SIGNAL_OVERSEAS.market()).isEqualTo(Market.OVERSEAS);
        }

        @Test
        @DisplayName("페이로드 종류가 틱/신호로 구분된다")
        void payloadKind_separatesTickFromSignal() {
            assertThat(ConsumedStream.TICK_DOMESTIC.payloadKind())
                    .isEqualTo(ConsumedStream.PayloadKind.TICK);
            assertThat(ConsumedStream.TICK_OVERSEAS.payloadKind())
                    .isEqualTo(ConsumedStream.PayloadKind.TICK);
            assertThat(ConsumedStream.SIGNAL_DOMESTIC.payloadKind())
                    .isEqualTo(ConsumedStream.PayloadKind.SIGNAL);
            assertThat(ConsumedStream.SIGNAL_OVERSEAS.payloadKind())
                    .isEqualTo(ConsumedStream.PayloadKind.SIGNAL);
        }
    }
}
