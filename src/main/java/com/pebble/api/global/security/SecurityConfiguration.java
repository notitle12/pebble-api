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
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
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
                        .requestMatchers(HttpMethod.GET, "/api/v1/members/me", "/api/v1/members/me/posts", "/api/v1/members/me/boards").hasRole("USER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/categories", "/api/v1/tags").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/members/me/profile").hasRole("USER")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/members/me/profile").hasRole("USER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/posts/search").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/projects", "/api/v1/projects/{projectId:[0-9]+}").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/posts", "/api/v1/posts/{postId:[0-9]+}",
                                "/api/v1/blogs/{handle}/posts", "/api/v1/blogs/{handle}/posts/{postKey}").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/members/{memberId:[0-9]+}/posts").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/members/{memberId:[0-9]+}/boards",
                                "/api/v1/members/{memberId:[0-9]+}/boards/{boardId:[0-9]+}/posts").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/boards").hasRole("USER")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/boards/{boardId:[0-9]+}").hasRole("USER")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/boards/{boardId:[0-9]+}").hasRole("USER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/posts").hasRole("USER")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/posts/{postId:[0-9]+}").hasRole("USER")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/posts/{postId:[0-9]+}").hasRole("USER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/projects").hasRole("USER")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/projects/{projectId:[0-9]+}").hasRole("USER")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/projects/{projectId:[0-9]+}").hasRole("USER")
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(errorHandler).accessDeniedHandler(errorHandler))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(userJwtAuthenticationConverter())))
                .build();
    }

    private JwtAuthenticationConverter userJwtAuthenticationConverter() {
        // 검증된 서버 발급 role claim만 HTTP 역할 권한으로 변환한다.
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
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
        CorsConfiguration getCors = new CorsConfiguration(cors);
        getCors.setAllowedMethods(List.of("GET"));
        source.registerCorsConfiguration("/api/v1/members/me", getCors);
        source.registerCorsConfiguration("/api/v1/categories", getCors);
        source.registerCorsConfiguration("/api/v1/tags", getCors);
        CorsConfiguration profileCors = new CorsConfiguration(cors);
        profileCors.setAllowedMethods(List.of("POST", "PATCH"));
        source.registerCorsConfiguration("/api/v1/members/me/profile", profileCors);
        source.registerCorsConfiguration("/api/v1/members/me/posts", getCors);
        source.registerCorsConfiguration("/api/v1/members/{memberId:[0-9]+}/posts", getCors);
        CorsConfiguration postCollectionCors = new CorsConfiguration(cors);
        postCollectionCors.setAllowedMethods(List.of("GET", "POST"));
        source.registerCorsConfiguration("/api/v1/posts", postCollectionCors);
        CorsConfiguration postDetailCors = new CorsConfiguration(cors);
        postDetailCors.setAllowedMethods(List.of("GET", "PATCH", "DELETE"));
        source.registerCorsConfiguration("/api/v1/posts/{postId:[0-9]+}", postDetailCors);
        source.registerCorsConfiguration("/api/v1/posts/search", getCors);
        CorsConfiguration projectCollectionCors = new CorsConfiguration(cors);
        projectCollectionCors.setAllowedMethods(List.of("GET", "POST"));
        source.registerCorsConfiguration("/api/v1/projects", projectCollectionCors);
        CorsConfiguration projectDetailCors = new CorsConfiguration(cors);
        projectDetailCors.setAllowedMethods(List.of("GET", "PATCH", "DELETE"));
        source.registerCorsConfiguration("/api/v1/projects/{projectId:[0-9]+}", projectDetailCors);
        source.registerCorsConfiguration("/api/v1/blogs/{handle}/posts", getCors);
        source.registerCorsConfiguration("/api/v1/blogs/{handle}/posts/{postKey}", getCors);
        source.registerCorsConfiguration("/api/v1/members/me/boards", getCors);
        source.registerCorsConfiguration("/api/v1/members/{memberId:[0-9]+}/boards", getCors);
        source.registerCorsConfiguration("/api/v1/members/{memberId:[0-9]+}/boards/{boardId:[0-9]+}/posts", getCors);
        CorsConfiguration boardCollectionCors = new CorsConfiguration(cors);
        boardCollectionCors.setAllowedMethods(List.of("POST"));
        source.registerCorsConfiguration("/api/v1/boards", boardCollectionCors);
        CorsConfiguration boardDetailCors = new CorsConfiguration(cors);
        boardDetailCors.setAllowedMethods(List.of("PATCH", "DELETE"));
        source.registerCorsConfiguration("/api/v1/boards/{boardId:[0-9]+}", boardDetailCors);
        return source;
    }
}
