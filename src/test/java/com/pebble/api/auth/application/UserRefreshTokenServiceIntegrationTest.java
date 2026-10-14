package com.pebble.api.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pebble.api.auth.domain.AuthException;
import com.pebble.api.auth.infrastructure.redis.RefreshTokenProperties;
import com.pebble.api.support.AuthenticationTestSupport;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
class UserRefreshTokenServiceIntegrationTest extends AuthenticationTestSupport {

    @Autowired
    UserRefreshTokenService tokens;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    RefreshTokenProperties properties;

    @Test
    void simultaneousRotationHasOneWinnerAndRevokesItsSuccessor() throws Exception {
        var original = tokens.issue(101, Instant.now());
        var snapshot = tokens.find(original.value());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> request = () -> {
            ready.countDown();
            start.await();
            try {
                return tokens.rotate(snapshot, Instant.now()).value();
            } catch (AuthException exception) {
                return "rejected";
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(request);
            var second = executor.submit(request);
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> results = List.of(first.get(), second.get());
            assertThat(results).filteredOn("rejected"::equals).hasSize(1);
            String winner = results.stream().filter(value -> !"rejected".equals(value)).findFirst().orElseThrow();
            assertThatThrownBy(() -> tokens.rotate(tokens.find(winner), Instant.now())).isInstanceOf(AuthException.class);
        }
    }

    @Test
    void lostResponseRetryRevokesWholeFamilyAfterMultipleRotations() {
        var first = tokens.issue(102, Instant.now());
        var firstSnapshot = tokens.find(first.value());
        var second = tokens.rotate(firstSnapshot, Instant.now());
        var third = tokens.rotate(tokens.find(second.value()), Instant.now());
        assertThat(redis.getExpire(key(firstSnapshot))).isGreaterThan(Duration.ofDays(29).toSeconds());
        redis.opsForHash().put(key(firstSnapshot), "idleExpiresAt", Instant.now().minusSeconds(1).toString());
        assertThatThrownBy(() -> tokens.rotate(tokens.find(first.value()), Instant.now())).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> tokens.rotate(tokens.find(third.value()), Instant.now())).isInstanceOf(AuthException.class);
        var independent = tokens.issue(102, Instant.now());
        assertThat(tokens.rotate(tokens.find(independent.value()), Instant.now()).value()).isNotBlank();
    }

    @Test
    void absoluteDeadlineIsPreservedAndCookieLifetimeIsCapped() {
        Instant now = Instant.now();
        var original = tokens.issue(103, now);
        var snapshot = tokens.find(original.value());
        String absolute = now.plus(Duration.ofDays(1)).toString();
        redis.opsForHash().put(key(snapshot), "absoluteExpiresAt", absolute);
        redis.opsForHash().put(key(snapshot), "familyCreatedAt", now.minus(Duration.ofDays(29)).toString());
        redis.opsForHash().put(key(snapshot), "idleExpiresAt", now.plus(Duration.ofDays(2)).toString());
        redis.expire(key(snapshot), Duration.ofDays(1));
        var rotated = tokens.rotate(tokens.find(original.value()), now);
        var next = tokens.find(rotated.value());
        assertThat(next.absoluteExpiresAt()).isEqualTo(absolute);
        assertThat(rotated.idleExpiresAt()).isEqualTo(Instant.parse(absolute));
        assertThat(redis.getExpire(key(next))).isBetween(86300L, 86400L);
    }

    @Test
    void expiredIdleAndAbsoluteTokensCannotRotate() {
        for (String field : List.of("idleExpiresAt", "absoluteExpiresAt")) {
            var issued = tokens.issue(104, Instant.now());
            var snapshot = tokens.find(issued.value());
            redis.opsForHash().put(key(snapshot), field, Instant.now().minusSeconds(1).toString());
            assertThatThrownBy(() -> tokens.rotate(tokens.find(issued.value()), Instant.now()))
                    .isInstanceOf(AuthException.class);
        }
        var expired = tokens.issue(104, Instant.now());
        redis.delete(key(tokens.find(expired.value())));
        assertThatThrownBy(() -> tokens.find(expired.value())).isInstanceOf(AuthException.class);
    }

    @Test
    void logoutAndRotationRaceCannotLeaveUsableSuccessor() throws Exception {
        var original = tokens.issue(105, Instant.now());
        var snapshot = tokens.find(original.value());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var rotated = executor.submit(() -> {
                start.await();
                try {
                    return tokens.rotate(snapshot, Instant.now()).value();
                } catch (AuthException exception) {
                    return "rejected";
                }
            });
            var logout = executor.submit(() -> {
                start.await();
                tokens.logout(original.value());
                return true;
            });
            start.countDown();
            String successor = rotated.get();
            assertThat(logout.get()).isTrue();
            if (!"rejected".equals(successor)) {
                assertThatThrownBy(() -> tokens.rotate(tokens.find(successor), Instant.now())).isInstanceOf(AuthException.class);
            }
            assertThatThrownBy(() -> tokens.rotate(snapshot, Instant.now())).isInstanceOf(AuthException.class);
        }
    }

    @Test
    void previousPepperAndUnversionedLegacyTokenMigrateAndRetainReplayDetection() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String nextSecret = Base64.getEncoder().encodeToString(secret);
        var upgraded = new UserRefreshTokenService(redis,
                new RefreshTokenProperties(nextSecret, "v2", Map.of("v1", properties.pepperBase64())));
        var original = tokens.issue(106, Instant.now());
        var old = tokens.find(original.value());
        redis.opsForHash().delete(key(old), "pepperVersion");
        var rotated = upgraded.rotate(upgraded.find(original.value()), Instant.now());
        var next = upgraded.find(rotated.value());
        assertThat(redis.opsForHash().get(key(next), "pepperVersion")).isEqualTo("v2");
        assertThat(next.familyId()).isEqualTo(old.familyId());
        assertThatThrownBy(() -> upgraded.rotate(upgraded.find(original.value()), Instant.now())).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> upgraded.rotate(next, Instant.now())).isInstanceOf(AuthException.class);
        var retired = new UserRefreshTokenService(redis, new RefreshTokenProperties(nextSecret, "v2", Map.of()));
        assertThatThrownBy(() -> retired.find(original.value())).isInstanceOf(AuthException.class);
    }

    @Test
    void missingOrCorruptFamilyNeverConsumesToken() {
        for (boolean corrupt : List.of(false, true)) {
            var original = tokens.issue(107, Instant.now());
            var snapshot = tokens.find(original.value());
            String family = "pebble:auth:user:refresh-family:" + snapshot.familyId();
            redis.delete(family);
            if (corrupt) {
                redis.opsForValue().set(family, "invalid", Duration.ofMinutes(1));
            }
            assertThatThrownBy(() -> tokens.rotate(snapshot, Instant.now())).isInstanceOf(AuthException.class);
            assertThat(redis.opsForHash().get(key(snapshot), "status")).isEqualTo("ACTIVE");
        }
    }

    @Test
    void wholeMemberRevocationRejectsLegacyAndRotatedFamiliesWithoutAffectingNewLoginOrOthers() {
        long memberId = com.pebble.api.global.id.TsidGenerator.generate();
        long otherId = com.pebble.api.global.id.TsidGenerator.generate();
        var legacy = tokens.issue(memberId, Instant.now());
        var legacySnapshot = tokens.find(legacy.value());
        redis.opsForHash().delete(key(legacySnapshot), "generation");
        var first = tokens.issue(memberId, Instant.now());
        var second = tokens.rotate(tokens.find(first.value()), Instant.now());
        var cachedSnapshot = tokens.find(second.value());
        var other = tokens.issue(otherId, Instant.now());
        tokens.revokeAll(memberId);
        for (String old : List.of(legacy.value(), first.value(), second.value()))
            assertThatThrownBy(() -> tokens.find(old)).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> tokens.rotate(cachedSnapshot, Instant.now())).isInstanceOf(AuthException.class);
        assertThat(tokens.rotate(tokens.find(other.value()), Instant.now()).value()).isNotBlank();
        var fresh = tokens.issue(memberId, Instant.now());
        tokens.logout(legacy.value());
        assertThat(tokens.rotate(tokens.find(fresh.value()), Instant.now()).value()).isNotBlank();
        tokens.revokeAll(memberId);
        assertThatThrownBy(() -> tokens.find(fresh.value())).isInstanceOf(AuthException.class);
    }

    @Test
    void generationIsRetainedUntilEveryReferencingFamilyExpires() {
        long memberId = com.pebble.api.global.id.TsidGenerator.generate();
        String generationKey = "pebble:auth:user:member:" + memberId + ":generation";
        tokens.revokeAll(memberId);
        redis.expire(generationKey, Duration.ofSeconds(1));
        var issued = tokens.issue(memberId, Instant.now());
        assertThat(redis.getExpire(generationKey)).isGreaterThan(Duration.ofDays(29).toSeconds());
        var snapshot = tokens.find(issued.value());
        redis.expire(generationKey, Duration.ofSeconds(1));
        var next = tokens.rotate(snapshot, Instant.now());
        assertThat(redis.getExpire(generationKey)).isGreaterThan(Duration.ofDays(29).toSeconds());
        assertThat(tokens.find(next.value()).memberId()).isEqualTo(memberId);
    }

    @Test
    void revocationAndRotationRaceNeverLeavesAUsableSuccessor() throws Exception {
        long memberId = com.pebble.api.global.id.TsidGenerator.generate();
        var issued = tokens.issue(memberId, Instant.now());
        var snapshot = tokens.find(issued.value());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var rotation = executor.submit(() -> {
                start.await();
                try { return tokens.rotate(snapshot, Instant.now()).value(); }
                catch (AuthException exception) { return "rejected"; }
            });
            var revocation = executor.submit(() -> { start.await(); tokens.revokeAll(memberId); return true; });
            start.countDown();
            String next = rotation.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(revocation.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            if (!"rejected".equals(next)) assertThatThrownBy(() -> tokens.find(next)).isInstanceOf(AuthException.class);
            assertThatThrownBy(() -> tokens.rotate(snapshot, Instant.now())).isInstanceOf(AuthException.class);
        }
    }

    private String key(UserRefreshTokenService.TokenSnapshot snapshot) {
        return "pebble:auth:user:refresh-token:" + snapshot.digest();
    }
}
