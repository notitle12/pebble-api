package com.pebble.api.auth.application;

import com.pebble.api.auth.domain.AuthError;
import com.pebble.api.auth.domain.AuthException;
import com.pebble.api.auth.infrastructure.redis.RefreshTokenCodec;
import com.pebble.api.auth.infrastructure.redis.RefreshTokenProperties;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class AdminRefreshTokenService {
    public static final Duration IDLE_TTL = Duration.ofHours(1);
    public static final Duration ABSOLUTE_TTL = Duration.ofHours(24);
    private static final DefaultRedisScript<Long> STORE = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 or redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
            redis.call('HSET', KEYS[1], 'subjectType', 'ADMIN', 'subjectId', ARGV[1],
                'role', ARGV[2], 'sessionId', ARGV[3], 'familyId', ARGV[3], 'status', 'ACTIVE',
                'issuedAt', ARGV[4], 'lastUsedAt', ARGV[4], 'familyCreatedAt', ARGV[4],
                'idleExpiresAtMillis', ARGV[5], 'absoluteExpiresAtMillis', ARGV[6], 'pepperVersion', ARGV[7])
            redis.call('PEXPIREAT', KEYS[1], ARGV[5])
            redis.call('HSET', KEYS[2], 'subjectId', ARGV[1], 'role', ARGV[2],
                'idleExpiresAtMillis', ARGV[5], 'absoluteExpiresAtMillis', ARGV[6])
            redis.call('PEXPIREAT', KEYS[2], ARGV[5])
            redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', ARGV[8])
            redis.call('ZADD', KEYS[3], ARGV[6], ARGV[3])
            -- 최근 세션이 오래된 세션보다 먼저 만료해도 계정 인덱스를 잃지 않는다.
            local last = redis.call('ZREVRANGE', KEYS[3], 0, 0, 'WITHSCORES')
            redis.call('PEXPIREAT', KEYS[3], last[2])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> REVOKE_ALL = new DefaultRedisScript<>("""
            local sessions = redis.call('ZRANGE', KEYS[1], 0, -1)
            for _, sid in ipairs(sessions) do redis.call('DEL', ARGV[1] .. sid) end
            redis.call('DEL', KEYS[1])
            return #sessions
            """, Long.class);
    private static final DefaultRedisScript<Long> TRANSITION;
    static {
        TRANSITION = new DefaultRedisScript<>();
        TRANSITION.setLocation(new ClassPathResource("redis/admin-refresh-transition.lua"));
        TRANSITION.setResultType(Long.class);
    }
    private final StringRedisTemplate redis;
    private final RefreshTokenCodec codec;

    public AdminRefreshTokenService(StringRedisTemplate redis, RefreshTokenProperties properties) {
        this.redis = redis;
        this.codec = new RefreshTokenCodec(properties);
    }

    public IssuedRefreshToken issue(long adminId, String role, String sid, Instant now) {
        String token = codec.randomToken();
        Instant idle = now.plus(IDLE_TTL);
        Instant absolute = now.plus(ABSOLUTE_TTL);
        Long result = redis.execute(STORE, List.of(tokenKey(codec.currentDigest(token)), sessionKey(sid), subjectKey(adminId)),
                Long.toString(adminId), role, sid, now.toString(), Long.toString(idle.toEpochMilli()),
                Long.toString(absolute.toEpochMilli()), codec.currentVersion(), Long.toString(now.toEpochMilli()));
        if (!Long.valueOf(1).equals(result)) throw new IllegalStateException("Admin session could not be stored");
        return new IssuedRefreshToken(token, idle);
    }

    public TokenSnapshot find(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        for (var entry : codec.digests(token).entrySet()) {
            Map<Object, Object> data = redis.opsForHash().entries(tokenKey(entry.getValue()));
            if (!entry.getKey().equals(data.get("pepperVersion")) || !"ADMIN".equals(data.get("subjectType"))) continue;
            try {
                return new TokenSnapshot(entry.getValue(), Long.parseLong((String) data.get("subjectId")),
                        (String) data.get("role"), (String) data.get("sessionId"),
                        Instant.ofEpochMilli(Long.parseLong((String) data.get("absoluteExpiresAtMillis"))));
            } catch (IllegalArgumentException | NullPointerException exception) { throw invalid(); }
        }
        throw invalid();
    }

    public IssuedRefreshToken rotate(TokenSnapshot snapshot, Instant now) {
        String token = codec.randomToken();
        Instant idle = now.plus(IDLE_TTL);
        if (idle.isAfter(snapshot.absoluteExpiresAt())) idle = snapshot.absoluteExpiresAt();
        Long result = transition(snapshot, "rotate", token, now, idle);
        if (Long.valueOf(-1).equals(result)) throw new AuthException(AuthError.REFRESH_TOKEN_REUSED);
        if (!Long.valueOf(1).equals(result)) throw invalid();
        return new IssuedRefreshToken(token, idle);
    }

    public void logout(String token) {
        if (token == null) return;
        try {
            TokenSnapshot snapshot = find(token);
            transition(snapshot, "logout", codec.randomToken(), Instant.now(), Instant.now());
        } catch (AuthException exception) {
            // 알 수 없는 쿠키도 제거할 수 있도록 멱등 처리한다. Redis 장애는 숨기지 않는다.
        }
    }

    public boolean isSessionActive(long adminId, String role, String sid, Instant now) {
        if (sid == null || !sid.matches("[a-f0-9-]{36}")) return false;
        Map<Object, Object> data = redis.opsForHash().entries(sessionKey(sid));
        try {
            return Long.toString(adminId).equals(data.get("subjectId")) && role.equals(data.get("role"))
                    && Long.parseLong((String) data.get("idleExpiresAtMillis")) > now.toEpochMilli()
                    && Long.parseLong((String) data.get("absoluteExpiresAtMillis")) > now.toEpochMilli();
        } catch (IllegalArgumentException | NullPointerException exception) { return false; }
    }

    public void revokeAll(long adminId) {
        Long result = redis.execute(REVOKE_ALL, List.of(subjectKey(adminId)), "pebble:auth:admin:session:");
        if (result == null) throw new IllegalStateException("Admin session revocation could not be confirmed");
    }

    private Long transition(TokenSnapshot snapshot, String action, String token, Instant now, Instant idle) {
        return redis.execute(TRANSITION,
                List.of(tokenKey(snapshot.digest()), sessionKey(snapshot.sessionId()), tokenKey(codec.currentDigest(token))),
                Long.toString(snapshot.adminId()), snapshot.sessionId(), snapshot.role(), action, now.toString(),
                Long.toString(idle.toEpochMilli()), codec.currentVersion());
    }
    private AuthException invalid() { return new AuthException(AuthError.INVALID_REFRESH_TOKEN); }
    private String tokenKey(String digest) { return "pebble:auth:admin:refresh-token:" + digest; }
    private String sessionKey(String sid) { return "pebble:auth:admin:session:" + sid; }
    private String subjectKey(long adminId) { return "pebble:auth:admin:subject:" + adminId; }
    public record IssuedRefreshToken(String value, Instant idleExpiresAt) {
        @Override public String toString() { return "IssuedRefreshToken[redacted]"; }
    }
    public record TokenSnapshot(String digest, long adminId, String role, String sessionId, Instant absoluteExpiresAt) { }
}
