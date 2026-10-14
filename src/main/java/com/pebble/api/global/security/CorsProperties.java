package com.pebble.api.global.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pebble.security.cors")
public record CorsProperties(List<String> allowedOrigins) {
}
