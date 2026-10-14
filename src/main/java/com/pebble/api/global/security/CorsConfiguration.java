package com.pebble.api.global.security;

import java.net.URI;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** 브라우저 Origin과 경로별 메서드 정책. 인증/인가 허용과는 별도로 검사한다. */
@Configuration
public class CorsConfiguration {
    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        for (String origin : properties.allowedOrigins()) {
            URI uri = URI.create(origin);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !uri.getRawPath().isEmpty() || origin.contains("*")) {
                throw new IllegalStateException("CORS requires exact HTTP(S) origins");
            }
        }
        org.springframework.web.cors.CorsConfiguration cors = new org.springframework.web.cors.CorsConfiguration();
        cors.setAllowedOrigins(properties.allowedOrigins());
        cors.setAllowedHeaders(List.of("Content-Type", "Authorization", "Accept"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(600L);
        cors.validateAllowCredentials();
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        var methodsByPath = new java.util.LinkedHashMap<String, java.util.Set<String>>();
        for (var endpoint : SecurityEndpoints.API) {
            methodsByPath.computeIfAbsent(endpoint.path(), ignored -> new java.util.LinkedHashSet<>())
                    .add(endpoint.method().name());
        }
        methodsByPath.forEach((path, methods) -> {
            var pathCors = new org.springframework.web.cors.CorsConfiguration(cors);
            pathCors.setAllowedMethods(List.copyOf(methods));
            source.registerCorsConfiguration(path, pathCors);
        });
        return source;
    }
}
