package com.pebble.api.auth.infrastructure.redis;

import com.pebble.api.member.domain.OAuthProvider;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class OAuthStateStore {

    private static final String KEY_PREFIX = "pebble:auth:oauth:";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private final StringRedisTemplate redisTemplate;

    public OAuthStateStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String issue(OAuthProvider provider, Duration stateTtl) {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redisTemplate.opsForValue().set(key(provider, state), "issued", stateTtl);
        return state;
    }

    public boolean consume(OAuthProvider provider, String state) {
        return "issued".equals(redisTemplate.opsForValue().getAndDelete(key(provider, state)));
    }

    private String key(OAuthProvider provider, String state) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(state.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + provider.name().toLowerCase(Locale.ROOT) + ":state:" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
