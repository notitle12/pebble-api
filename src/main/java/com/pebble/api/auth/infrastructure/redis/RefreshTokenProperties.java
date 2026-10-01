package com.pebble.api.auth.infrastructure.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pebble.auth.refresh-token")
public record RefreshTokenProperties(String pepperBase64) {

    @Override
    public String toString() {
        return "RefreshTokenProperties[redacted]";
    }
}
