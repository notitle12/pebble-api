package com.pebble.api.auth.application;

import com.pebble.api.auth.infrastructure.redis.RefreshTokenProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class UserRefreshTokenService {

    public static final Duration IDLE_TTL = Duration.ofDays(14);
    public static final Duration ABSOLUTE_TTL = Duration.ofDays(30);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final DefaultRedisScript<Long> STORE_TOKEN = new DefaultRedisScript<>("""
            redis.call('HSET', KEYS[1],
                'subjectType', 'USER',
                'subjectId', ARGV[1],
                'sessionId', ARGV[2],
                'familyId', ARGV[3],
                'status', 'ACTIVE',
                'issuedAt', ARGV[4],
                'lastUsedAt', ARGV[4],
                'familyCreatedAt', ARGV[4],
                'idleExpiresAt', ARGV[5],
                'absoluteExpiresAt', ARGV[6])
            redis.call('PEXPIRE', KEYS[1], ARGV[7])
            redis.call('SADD', KEYS[2], ARGV[8])
            redis.call('PEXPIRE', KEYS[2], ARGV[9])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final byte[] pepper;

    public UserRefreshTokenService(StringRedisTemplate redisTemplate, RefreshTokenProperties properties) {
        this.redisTemplate = redisTemplate;
        try {
            this.pepper = Base64.getDecoder().decode(properties.pepperBase64());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalStateException("Refresh Token pepper is not configured", exception);
        }
        if (pepper.length < 32) {
            throw new IllegalStateException("Refresh Token pepper must contain at least 32 bytes");
        }
    }

    public IssuedRefreshToken issue(long memberId, Instant now) {
        byte[] randomToken = new byte[32];
        SECURE_RANDOM.nextBytes(randomToken);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomToken);
        String digest = digest(token);
        String familyId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        Instant idleExpiresAt = now.plus(IDLE_TTL);
        Instant absoluteExpiresAt = now.plus(ABSOLUTE_TTL);

        redisTemplate.execute(STORE_TOKEN,
                List.of(tokenKey(digest), familyKey(familyId)),
                Long.toString(memberId),
                sessionId,
                familyId,
                now.toString(),
                idleExpiresAt.toString(),
                absoluteExpiresAt.toString(),
                Long.toString(IDLE_TTL.toMillis()),
                digest,
                Long.toString(ABSOLUTE_TTL.toMillis()));
        return new IssuedRefreshToken(token, idleExpiresAt);
    }

    private String digest(String token) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(token.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
        }
    }

    private String tokenKey(String digest) {
        return "pebble:auth:user:refresh-token:" + digest;
    }

    private String familyKey(String familyId) {
        return "pebble:auth:user:refresh-family:" + familyId;
    }

    public record IssuedRefreshToken(String value, Instant idleExpiresAt) {
    }
}
