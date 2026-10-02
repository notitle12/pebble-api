package com.pebble.api.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pebble.api.auth.domain.AuthException;
import com.pebble.api.auth.infrastructure.redis.RefreshTokenProperties;
import com.pebble.api.support.AuthenticationTestSupport;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
class AdminRefreshTokenServiceIntegrationTest extends AuthenticationTestSupport {
    @Autowired AdminRefreshTokenService tokens;
    @Autowired StringRedisTemplate redis;
    @Autowired AdminAuthRateLimiter limits;

    @Test
    void concurrentRotationCanSucceedOnlyOnceAndReuseRevokesTheWinner() throws Exception {
        long id = 41001;
        String sid = UUID.randomUUID().toString();
        var original = tokens.issue(id, "MANAGER", sid, Instant.now());
        var snapshot = tokens.find(original.value());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var jobs = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
                try { return tokens.rotate(snapshot, Instant.now()); }
                catch (AuthException exception) { return null; }
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int successes = 0;
            for (var job : jobs) if (job.get(5, TimeUnit.SECONDS) != null) successes++;
            assertThat(successes).isEqualTo(1);
            assertThat(tokens.isSessionActive(id, "MANAGER", sid, Instant.now())).isFalse();
            assertThatThrownBy(() -> tokens.rotate(snapshot, Instant.now())).isInstanceOf(AuthException.class);
        } finally { tokens.revokeAll(id); }
    }

    @Test
    void logoutWithConsumedTokenRevokesItsRotatedFamilyAndOtherSessionsStayActive() {
        long id = 41002;
        String sid = UUID.randomUUID().toString();
        String other = UUID.randomUUID().toString();
        try {
            var original = tokens.issue(id, "MASTER", sid, Instant.now());
            var next = tokens.rotate(tokens.find(original.value()), Instant.now());
            tokens.issue(id, "MASTER", other, Instant.now());
            tokens.logout(original.value());
            assertThat(tokens.isSessionActive(id, "MASTER", sid, Instant.now())).isFalse();
            assertThat(tokens.isSessionActive(id, "MASTER", other, Instant.now())).isTrue();
            assertThatThrownBy(() -> tokens.rotate(tokens.find(next.value()), Instant.now())).isInstanceOf(AuthException.class);
            tokens.logout(original.value());
            tokens.logout(null);
            tokens.logout("invalid");
        } finally { tokens.revokeAll(id); }
    }

    @Test
    void rotationPreservesAbsoluteDeadlineAndNeverStoresTheRawCredential() {
        long id = 41003;
        String sid = UUID.randomUUID().toString();
        try {
            var original = tokens.issue(id, "MANAGER", sid, Instant.now());
            var snapshot = tokens.find(original.value());
            String oldKey = "pebble:auth:admin:refresh-token:" + snapshot.digest();
            String sessionKey = "pebble:auth:admin:session:" + sid;
            Instant deadline = Instant.now().plusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            // 마지막 갱신으로 유휴 만료가 연장된 채 절대 만료 직전에 도달한 세션을 재현한다.
            redis.opsForHash().putAll(oldKey, Map.of("absoluteExpiresAtMillis", Long.toString(deadline.toEpochMilli()),
                    "idleExpiresAtMillis", Long.toString(deadline.toEpochMilli())));
            redis.opsForHash().putAll(sessionKey, Map.of("absoluteExpiresAtMillis", Long.toString(deadline.toEpochMilli()),
                    "idleExpiresAtMillis", Long.toString(deadline.toEpochMilli())));
            var nearExpiry = tokens.find(original.value());
            var next = tokens.rotate(nearExpiry, Instant.now());
            var rotated = tokens.find(next.value());
            assertThat(next.idleExpiresAt()).isEqualTo(deadline);
            assertThat(rotated.absoluteExpiresAt()).isEqualTo(deadline);
            String nextKey = "pebble:auth:admin:refresh-token:" + rotated.digest();
            assertThat(redis.getExpire(nextKey, TimeUnit.MILLISECONDS)).isBetween(1L, 30000L);
            assertThat(redis.getExpire(oldKey, TimeUnit.MILLISECONDS)).isBetween(1L, 30000L);
            for (String key : List.of(oldKey, nextKey, sessionKey)) {
                assertThat(key).doesNotContain(original.value(), next.value());
                assertThat(redis.opsForHash().entries(key).values()).doesNotContain(original.value(), next.value());
            }
        } finally { tokens.revokeAll(id); }
    }

    @Test
    void idleExpiryRejectsRefreshAndAccessSession() {
        long id = 41004;
        String sid = UUID.randomUUID().toString();
        try {
            var original = tokens.issue(id, "MANAGER", sid, Instant.now());
            var snapshot = tokens.find(original.value());
            redis.opsForHash().put("pebble:auth:admin:refresh-token:" + snapshot.digest(), "idleExpiresAtMillis", "1");
            redis.delete("pebble:auth:admin:session:" + sid);
            assertThat(tokens.isSessionActive(id, "MANAGER", sid, Instant.now())).isFalse();
            assertThatThrownBy(() -> tokens.rotate(snapshot, Instant.now())).isInstanceOf(AuthException.class);
        } finally { tokens.revokeAll(id); }
    }

    @Test
    void previousPepperRotatesToCurrentAndOldReplayRevokesCurrentSession() {
        long id = 41005;
        String sid = UUID.randomUUID().toString();
        String oldPepper = pepper();
        var old = new AdminRefreshTokenService(redis, new RefreshTokenProperties(oldPepper, "old", Map.of()));
        var current = new AdminRefreshTokenService(redis, new RefreshTokenProperties(pepper(), "current", Map.of("old", oldPepper)));
        try {
            var original = old.issue(id, "MASTER", sid, Instant.now());
            var next = current.rotate(current.find(original.value()), Instant.now());
            assertThat(current.isSessionActive(id, "MASTER", sid, Instant.now())).isTrue();
            var snapshot = current.find(next.value());
            assertThat(redis.opsForHash().get("pebble:auth:admin:refresh-token:" + snapshot.digest(), "pepperVersion")).isEqualTo("current");
            assertThatThrownBy(() -> current.rotate(current.find(original.value()), Instant.now())).isInstanceOf(AuthException.class);
            assertThat(current.isSessionActive(id, "MASTER", sid, Instant.now())).isFalse();
        } finally { current.revokeAll(id); }
    }

    private String pepper() {
        byte[] value = new byte[32];
        new SecureRandom().nextBytes(value);
        return Base64.getEncoder().encodeToString(value);
    }

    @Test
    void ipAndRefreshAccountLimitsApplyAcrossDifferentCredentialsAndAddresses() {
        String loginIp = "127.2.1.41";
        String refreshIp = "127.2.2.41";
        String prefix = UUID.randomUUID().toString();
        for (int i = 0; i < 60; i++) limits.login(prefix + i, loginIp);
        assertThatThrownBy(() -> limits.login(prefix + "overflow", loginIp))
                .isInstanceOf(AuthException.class).extracting(exception -> ((AuthException) exception).error().name()).isEqualTo("RATE_LIMITED");
        for (int i = 0; i < 120; i++) limits.refreshIp(refreshIp);
        assertThatThrownBy(() -> limits.refreshIp(refreshIp)).isInstanceOf(AuthException.class);
        long id = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        for (int i = 0; i < 60; i++) limits.refresh(id, "127.3.1." + i);
        assertThatThrownBy(() -> limits.refresh(id, "127.3.2.1")).isInstanceOf(AuthException.class);
    }
}
