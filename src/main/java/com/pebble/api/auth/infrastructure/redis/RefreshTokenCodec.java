package com.pebble.api.auth.infrastructure.redis;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 주체별 저장·회전 정책과 독립적인 불투명 토큰 생성 및 pepper 계산. */
public final class RefreshTokenCodec {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Map<String, byte[]> peppers;
    private final String currentVersion;

    public RefreshTokenCodec(RefreshTokenProperties properties) {
        currentVersion = properties.pepperVersion() == null ? "v1" : properties.pepperVersion();
        Map<String, byte[]> configured = new LinkedHashMap<>();
        add(configured, currentVersion, properties.pepperBase64());
        if (properties.previousPeppers() != null) properties.previousPeppers().forEach((version, secret) -> add(configured, version, secret));
        peppers = Map.copyOf(configured);
    }

    private void add(Map<String, byte[]> configured, String version, String value) {
        if (version == null || !version.matches("[a-zA-Z0-9_-]{1,32}") || configured.containsKey(version))
            throw new IllegalStateException("Invalid or duplicate Refresh Token pepper version");
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length < 32) throw new IllegalStateException("Refresh Token pepper must contain at least 32 bytes");
            configured.put(version, bytes);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalStateException("Refresh Token pepper is not configured");
        }
    }

    public String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String currentVersion() { return currentVersion; }
    public String currentDigest(String token) { return digest(token, peppers.get(currentVersion)); }
    public Map<String, String> digests(String token) {
        Map<String, String> values = new LinkedHashMap<>();
        peppers.forEach((version, pepper) -> values.put(version, digest(token, pepper)));
        return values;
    }

    private String digest(String token, byte[] pepper) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(token.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable");
        }
    }
}
