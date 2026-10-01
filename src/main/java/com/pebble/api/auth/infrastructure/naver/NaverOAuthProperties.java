package com.pebble.api.auth.infrastructure.naver;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pebble.auth.naver")
public record NaverOAuthProperties(
        String clientId,
        String clientSecret,
        String redirectUri,
        String authorizationUri,
        String tokenUri,
        String profileUri,
        Duration stateTtl) {

    @Override
    public String toString() {
        return "NaverOAuthProperties[clientSecret=redacted]";
    }
}
