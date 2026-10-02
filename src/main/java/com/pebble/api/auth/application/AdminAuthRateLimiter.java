package com.pebble.api.auth.application;

import com.pebble.api.auth.domain.AuthError;
import com.pebble.api.auth.domain.AuthException;
import com.pebble.api.auth.infrastructure.redis.AdminRateLimitProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(AdminRateLimitProperties.class)
public class AdminAuthRateLimiter {
    private static final DefaultRedisScript<Long> LIMIT = new DefaultRedisScript<>("""
            local exceeded = false
            for i, key in ipairs(KEYS) do
                local count = redis.call('INCR', key)
                if count == 1 then redis.call('PEXPIRE', key, ARGV[3]) end
                if count > tonumber(ARGV[i]) then exceeded = true end
            end
            if exceeded then return 0 end
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final AdminRateLimitProperties properties;

    public void login(String loginId, String ip) {
        check("login", loginId, ip, properties.loginAccountLimit(), properties.loginIpLimit(), properties.loginWindow().toMillis());
    }
    public void refresh(long adminId, String ip) {
        check("refresh", Long.toString(adminId), ip, properties.refreshAccountLimit(), properties.refreshIpLimit(), properties.refreshWindow().toMillis());
    }
    public void refreshIp(String ip) {
        // 쿠키 조회 전 IP 제한을 적용해 무작위 토큰으로 Redis 조회를 남용하지 못하게 한다.
        check("refresh-invalid", ip, ip, properties.refreshIpLimit(), properties.refreshIpLimit(), properties.refreshWindow().toMillis());
    }
    private void check(String action, String subject, String ip, int accountLimit, int ipLimit, long window) {
        Long result = redis.execute(LIMIT,
                List.of("pebble:auth:admin:rate:" + action + ":subject:" + hash(subject),
                        "pebble:auth:admin:rate:" + action + ":ip:" + hash(ip)),
                Integer.toString(accountLimit), Integer.toString(ipLimit), Long.toString(window));
        if (!Long.valueOf(1).equals(result)) throw new AuthException(AuthError.RATE_LIMITED);
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable"); }
    }
}
