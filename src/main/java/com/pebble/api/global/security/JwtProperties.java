package com.pebble.api.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pebble.auth.jwt")
public record JwtProperties(
        String issuer,
        String audience,
        String keyId,
        String privateKeyBase64,
        String publicKeyBase64) {

    @Override
    public String toString() {
        return "JwtProperties[redacted]";
    }
}
