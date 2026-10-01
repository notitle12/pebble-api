package com.pebble.api.auth.infrastructure.redis;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pebble.auth.refresh-token")
public record RefreshTokenProperties(String pepperBase64, String pepperVersion, Map<String, String> previousPeppers) {

    @Override
    public String toString() {
        return "RefreshTokenProperties[redacted]";
    }
}
