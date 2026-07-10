package com.aaa.notifier.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("SafeMdc")
class SafeMdcTest {

    @BeforeEach
    void setUp() {
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("put/get — 저장한 값을 조회한다")
    void putThenGet_returnsStoredValue() {
        SafeMdc.put("k", "v");
        assertThat(SafeMdc.get("k")).isEqualTo("v");
    }

    @Test
    @DisplayName("put — null 값도 저장 가능하다")
    void put_allowsNullValue() {
        SafeMdc.put("k", null);
        assertThat(SafeMdc.get("k")).isNull();
    }

    @Test
    @DisplayName("put — null 키는 IllegalArgumentException")
    void put_nullKey_throws() {
        assertThatThrownBy(() -> SafeMdc.put(null, "v"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("remove — 해당 키만 제거한다")
    void remove_removesKey() {
        SafeMdc.put("k", "v");
        SafeMdc.remove("k");
        assertThat(SafeMdc.get("k")).isNull();
    }

    @Test
    @DisplayName("remove — null 키는 IllegalArgumentException")
    void remove_nullKey_throws() {
        assertThatThrownBy(() -> SafeMdc.remove(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("clear — 모든 키를 제거한다")
    void clear_removesAllKeys() {
        SafeMdc.put("a", "1");
        SafeMdc.put("b", "2");
        SafeMdc.clear();
        assertThat(SafeMdc.get("a")).isNull();
        assertThat(SafeMdc.get("b")).isNull();
    }
}
