package com.pebble.api.auth.application;

import com.pebble.api.auth.infrastructure.redis.RefreshTokenProperties;
import com.pebble.api.auth.infrastructure.redis.RefreshTokenCodec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.pebble.api.auth.domain.AuthError;
import com.pebble.api.auth.domain.AuthException;
import org.springframework.core.io.ClassPathResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class UserRefreshTokenService {

    public static final Duration IDLE_TTL = Duration.ofDays(14);
    public static final Duration ABSOLUTE_TTL = Duration.ofDays(30);
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
    private final RefreshTokenCodec codec;
    private final String currentVersion;

    public UserRefreshTokenService(StringRedisTemplate redisTemplate, RefreshTokenProperties properties) {
        this.redisTemplate = redisTemplate;
        this.codec = new RefreshTokenCodec(properties);
        this.currentVersion = codec.currentVersion();
    }

    public IssuedRefreshToken issue(long memberId, Instant now) {
        String token = codec.randomToken();
        String digest = codec.currentDigest(token);
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
        for (Map.Entry<String, String> entry : codec.digests(token).entrySet()) {
            String digest = entry.getValue();
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
        String token = codec.randomToken();
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
        transition(snapshot, "revoke", codec.randomToken(), Instant.now(), Instant.now());
    }

    public void logout(String token) {
        if (token == null) {
            return;
        }
        try {
            TokenSnapshot snapshot = find(token);
            transition(snapshot, "logout", codec.randomToken(), Instant.now(), Instant.now());
        } catch (AuthException exception) {
            // 알 수 없거나 만료된 쿠키도 삭제할 수 있도록 로그아웃은 멱등 처리한다.
        }
    }

    private long transition(TokenSnapshot snapshot, String action, String token, Instant now, Instant expiresAt) {
        String digest = codec.currentDigest(token);
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

    public record TokenSnapshot(String digest, String familyId, long memberId,
                                String idleExpiresAt, String absoluteExpiresAt) {
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
