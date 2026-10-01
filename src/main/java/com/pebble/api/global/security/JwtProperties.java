package com.pebble.api.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pebble.auth.jwt")
public record JwtProperties(
        String issuer,
        String audience,
        String privateKeyLocation,
        String publicKeyLocation,
        String keyId) {
}
