package com.pebble.api.global.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TsidUtilTest {

    @Test
    @DisplayName("TSID 생성 시 0보다 큰 Long ID가 반환된다")
    void nextId_success() {
        Long id = TsidUtil.nextId();
        assertThat(id).isNotNull();
        assertThat(id).isGreaterThan(0L);
    }

    @Test
    @DisplayName("TSID 문자열 생성 시 13자리 문자열이 반환된다")
    void nextStringId_success() {
        String stringId = TsidUtil.nextStringId();
        assertThat(stringId).isNotNull();
        assertThat(stringId).hasSize(13);
    }

    @Test
    @DisplayName("연속 생성된 TSID는 고유성을 보장한다")
    void tsid_uniqueness() {
        int count = 1000;
        Set<Long> ids = new HashSet<>(count);

        for (int i = 0; i < count; i++) {
            ids.add(TsidUtil.nextId());
        }

        assertThat(ids).hasSize(count);
    }

    @Test
    @DisplayName("Long 형태와 String 형태 간 상호 변환이 정확하게 동작한다")
    void conversion_between_long_and_string() {
        Long originalId = TsidUtil.nextId();
        String stringId = TsidUtil.toString(originalId);
        Long convertedId = TsidUtil.toLong(stringId);

        assertThat(convertedId).isEqualTo(originalId);
    }
}
