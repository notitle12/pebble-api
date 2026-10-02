package com.pebble.api.auth.infrastructure.redis;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "pebble.auth.admin-rate-limit")
public record AdminRateLimitProperties(@DefaultValue("10") int loginAccountLimit,
                                      @DefaultValue("60") int loginIpLimit,
                                      @DefaultValue("15m") Duration loginWindow,
                                      @DefaultValue("60") int refreshAccountLimit,
                                      @DefaultValue("120") int refreshIpLimit,
                                      @DefaultValue("1m") Duration refreshWindow) {
    public AdminRateLimitProperties {
        if (loginAccountLimit < 1 || loginIpLimit < 1 || refreshAccountLimit < 1 || refreshIpLimit < 1
                || loginWindow == null || loginWindow.toMillis() < 1000 || refreshWindow == null || refreshWindow.toMillis() < 1000)
            throw new IllegalArgumentException("Invalid admin rate limit configuration");
    }
}
