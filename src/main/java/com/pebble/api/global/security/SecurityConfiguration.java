package com.pebble.api.global.security;

import java.util.List;
import java.net.URI;
import org.springframework.web.filter.CorsFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiSecurityErrorHandler errorHandler, CorsProperties corsProperties) throws Exception {
        return http
                .cors(Customizer.withDefaults())
                .addFilterBefore(new CookieOriginFilter(corsProperties, errorHandler), CorsFilter.class)
                // OAuth state와 필수 Origin 검증을 적용한 경로만 기본 CSRF 토큰 검사에서 제외한다.
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/naver/authorization"),
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/naver/login"),
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/token/refresh"),
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/logout")))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/naver/authorization", "/api/v1/auth/naver/login",
                                "/api/v1/auth/token/refresh", "/api/v1/auth/logout").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(errorHandler).accessDeniedHandler(errorHandler))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler)
                        .jwt(Customizer.withDefaults()))
                .build();
    }

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
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.allowedOrigins());
        cors.setAllowedMethods(List.of("POST"));
        cors.setAllowedHeaders(List.of("Content-Type", "Authorization", "Accept"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(600L);
        cors.validateAllowCredentials();
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/auth/naver/**", cors);
        source.registerCorsConfiguration("/api/v1/auth/token/refresh", cors);
        source.registerCorsConfiguration("/api/v1/auth/logout", cors);
        return source;
    }
}
