package com.pebble.api.global.presentation.trace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TraceIdGeneratorTest {

    @Test
    void generatesUlidFormatTraceIds() {
        String traceId = TraceIdGenerator.generate();

        assertThat(traceId).matches("[0-9A-HJKMNP-TV-Z]{26}");
    }

    @Test
    void generatesDifferentTraceIdsForSeparateRequests() {
        assertThat(TraceIdGenerator.generate()).isNotEqualTo(TraceIdGenerator.generate());
    }
}
