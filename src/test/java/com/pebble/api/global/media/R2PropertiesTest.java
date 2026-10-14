package com.pebble.api.global.media;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class R2PropertiesTest {
    @Test
    void redactsCredentialValuesFromStringRepresentation() {
        R2Properties properties = new R2Properties("https://example.r2.cloudflarestorage.com", "access-secret", "secret-secret", "private-bucket", "auto");
        assertThat(properties.toString()).contains("redacted")
                .doesNotContain("access-secret", "secret-secret", "private-bucket");
    }
}
