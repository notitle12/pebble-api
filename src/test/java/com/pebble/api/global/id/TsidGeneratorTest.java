package com.pebble.api.global.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TsidGeneratorTest {

    @Test
    void generatesPositiveUniqueIds() {
        Set<Long> ids = new HashSet<>();

        for (int i = 0; i < 1000; i++) {
            ids.add(TsidGenerator.generate());
        }

        assertThat(ids).hasSize(1000).allMatch(id -> id > 0);
    }
}
