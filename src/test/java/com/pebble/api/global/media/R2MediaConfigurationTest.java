package com.pebble.api.global.media;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class R2MediaConfigurationTest {
    private final R2MediaConfiguration configuration = new R2MediaConfiguration();

    @Test
    void acceptsRootHttpsEndpointWithAutoRegionAndValidR2BucketName() {
        assertThatCode(() -> configuration.validate(properties("https://example.r2.cloudflarestorage.com/", "auto", "pebble-media")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNonHttpsOrNonRootEndpointAndInvalidRegionOrBucket() {
        assertThatThrownBy(() -> configuration.validate(properties("http://example.r2.cloudflarestorage.com", "auto", "pebble-media")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> configuration.validate(properties("https://example.r2.cloudflarestorage.com/account", "auto", "pebble-media")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> configuration.validate(properties("https://example.r2.cloudflarestorage.com", "us-east-1", "pebble-media")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> configuration.validate(properties("https://example.r2.cloudflarestorage.com", "auto", "Pebble_Media")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidEndpointErrorDoesNotEchoEndpointOrCredentials() {
        R2Properties properties = new R2Properties("https://user:secret@example.invalid/private?token=private", "access-key", "secret-key", "pebble-media", "auto");
        assertThatThrownBy(() -> configuration.validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("secret").hasMessageNotContaining("access-key").hasMessageNotContaining("example.invalid");
    }

    private R2Properties properties(String endpoint, String region, String bucket) {
        return new R2Properties(endpoint, "test-access", "test-secret", bucket, region);
    }
}
