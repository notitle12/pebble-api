package com.pebble.api.auth.application;

import com.pebble.api.auth.infrastructure.redis.RefreshTokenProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import com.pebble.api.auth.domain.AuthError;
import com.pebble.api.auth.domain.AuthException;
import org.springframework.core.io.ClassPathResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
                'absoluteExpiresAt', ARGV[6], 'pepperVersion', ARGV[10])
            redis.call('PEXPIREAT', KEYS[1], ARGV[7])
            redis.call('SADD', KEYS[2], ARGV[8])
            redis.call('PEXPIREAT', KEYS[2], ARGV[9])
            return 1
            """, Long.class);

    private static final Logger log = LoggerFactory.getLogger(UserRefreshTokenService.class);
    private static final DefaultRedisScript<Long> TRANSITION = transitionScript();

    private static DefaultRedisScript<Long> transitionScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/user-refresh-transition.lua"));
        script.setResultType(Long.class);
        return script;
    }

    private final StringRedisTemplate redisTemplate;
    private final Map<String, byte[]> peppers;
    private final String currentVersion;

    public UserRefreshTokenService(StringRedisTemplate redisTemplate, RefreshTokenProperties properties) {
        this.redisTemplate = redisTemplate;
        this.currentVersion = properties.pepperVersion() == null ? "v1" : properties.pepperVersion();
        if (!currentVersion.matches("[a-zA-Z0-9_-]{1,32}")) {
            throw new IllegalStateException("Invalid Refresh Token pepper version");
        }
        Map<String, byte[]> configured = new LinkedHashMap<>();
        configured.put(currentVersion, decodePepper(properties.pepperBase64()));
        if (properties.previousPeppers() != null) {
            properties.previousPeppers().forEach((version, secret) -> {
                if (!version.matches("[a-zA-Z0-9_-]{1,32}") || configured.containsKey(version)) {
                    throw new IllegalStateException("Invalid or duplicate Refresh Token pepper version");
                }
                configured.put(version, decodePepper(secret));
            });
        }
        this.peppers = Map.copyOf(configured);
    }

    private byte[] decodePepper(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length < 32) {
                throw new IllegalStateException("Refresh Token pepper must contain at least 32 bytes");
            }
            return decoded;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalStateException("Refresh Token pepper is not configured");
        }
    }

    public IssuedRefreshToken issue(long memberId, Instant now) {
        String token = randomToken();
        String digest = digest(token, peppers.get(currentVersion));
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
                Long.toString(idleExpiresAt.toEpochMilli()),
                digest,
                Long.toString(absoluteExpiresAt.toEpochMilli()), currentVersion);
        return new IssuedRefreshToken(token, idleExpiresAt);
    }

    public TokenSnapshot find(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new AuthException(AuthError.INVALID_REFRESH_TOKEN);
        }
        for (Map.Entry<String, byte[]> entry : peppers.entrySet()) {
            String digest = digest(token, entry.getValue());
            Map<Object, Object> data = redisTemplate.opsForHash().entries(tokenKey(digest));
            if (data.isEmpty()) {
                continue;
            }
            // 버전이 없던 초기 세션은 기존 v1 pepper로만 읽는다.
            String version = (String) data.getOrDefault("pepperVersion", "v1");
            if (!entry.getKey().equals(version) || !"USER".equals(data.get("subjectType"))) {
                continue;
            }
            String familyId = (String) data.get("familyId");
            if (Boolean.TRUE.equals(redisTemplate.hasKey(familyKey(familyId) + ":revoked"))) {
                throw new AuthException(AuthError.INVALID_REFRESH_TOKEN);
            }
            return new TokenSnapshot(digest, familyId,
                    Long.parseLong((String) data.get("subjectId")),
                    (String) data.get("idleExpiresAt"), (String) data.get("absoluteExpiresAt"));
        }
        throw new AuthException(AuthError.INVALID_REFRESH_TOKEN);
    }

    public IssuedRefreshToken rotate(TokenSnapshot snapshot, Instant now) {
        String token = randomToken();
        Instant expiresAt = now.plus(IDLE_TTL);
        Instant absolute = Instant.parse(snapshot.absoluteExpiresAt());
        if (expiresAt.isAfter(absolute)) {
            expiresAt = absolute;
        }
        long result = transition(snapshot, "rotate", token, now, expiresAt);
        if (result == -1) {
            log.warn("Refresh Token 재사용 탐지: subjectId={}, familyId={}", snapshot.memberId(), snapshot.familyId());
        }
        if (result != 1) {
            throw new AuthException(AuthError.INVALID_REFRESH_TOKEN);
        }
        return new IssuedRefreshToken(token, expiresAt);
    }

    public void revoke(TokenSnapshot snapshot) {
        transition(snapshot, "revoke", randomToken(), Instant.now(), Instant.now());
    }

    public void logout(String token) {
        if (token == null) {
            return;
        }
        try {
            TokenSnapshot snapshot = find(token);
            transition(snapshot, "logout", randomToken(), Instant.now(), Instant.now());
        } catch (AuthException exception) {
            // 알 수 없거나 만료된 쿠키도 삭제할 수 있도록 로그아웃은 멱등 처리한다.
        }
    }

    private long transition(TokenSnapshot snapshot, String action, String token, Instant now, Instant expiresAt) {
        String digest = digest(token, peppers.get(currentVersion));
        Long result = redisTemplate.execute(TRANSITION,
                List.of(tokenKey(snapshot.digest()), familyKey(snapshot.familyId()),
                        familyKey(snapshot.familyId()) + ":revoked", tokenKey(digest)),
                snapshot.familyId(), Long.toString(snapshot.memberId()), snapshot.absoluteExpiresAt(),
                snapshot.idleExpiresAt(), Long.toString(Instant.parse(snapshot.absoluteExpiresAt()).toEpochMilli()),
                action, Long.toString(Instant.parse(snapshot.idleExpiresAt()).toEpochMilli()),
                Long.toString(IDLE_TTL.toMillis()), now.toString(), expiresAt.toString(), currentVersion,
                Long.toString(expiresAt.toEpochMilli()), digest);
        if (result == null) {
            throw new IllegalStateException("Refresh Token transition failed");
        }
        return result;
    }

    private String randomToken() {
        byte[] random = new byte[32];
        SECURE_RANDOM.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    public record TokenSnapshot(String digest, String familyId, long memberId,
                                String idleExpiresAt, String absoluteExpiresAt) {
    }

    private String digest(String token, byte[] pepper) {
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
