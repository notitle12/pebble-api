package com.pebble.api.global.security;

import com.pebble.api.auth.application.AdminSessionService;
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
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiSecurityErrorHandler errorHandler, CorsProperties corsProperties,
                                            AdminSessionService adminSessions) throws Exception {
        JwtAuthenticationConverter converter = userJwtAuthenticationConverter();
        return http
                .cors(Customizer.withDefaults())
                .addFilterBefore(new CookieOriginFilter(corsProperties, errorHandler), CorsFilter.class)
                // OAuth state와 필수 Origin 검증을 적용한 경로만 기본 CSRF 토큰 검사에서 제외한다.
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/naver/authorization"),
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/naver/login"),
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/naver/withdrawal/cancel"),
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/token/refresh"),
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/logout"),
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/admin/auth/login"),
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/admin/auth/token/refresh"),
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/admin/auth/logout")))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/naver/authorization", "/api/v1/auth/naver/login",
                                "/api/v1/auth/naver/withdrawal/cancel",
                                "/api/v1/auth/token/refresh", "/api/v1/auth/logout",
                                "/api/v1/admin/auth/login", "/api/v1/admin/auth/token/refresh", "/api/v1/admin/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/admin-accounts").hasRole("MASTER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/categories", "/api/v1/admin/tags").hasAnyRole("MANAGER", "MASTER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/categories", "/api/v1/admin/tags").hasAnyRole("MANAGER", "MASTER")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/categories/{id:[0-9]+}",
                                "/api/v1/admin/tags/{id:[0-9]+}").hasAnyRole("MANAGER", "MASTER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/post-comments", "/api/v1/admin/project-comments")
                            .hasAnyRole("MANAGER", "MASTER")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/admin/post-comments/{id:[0-9]+}",
                                "/api/v1/admin/project-comments/{id:[0-9]+}").hasAnyRole("MANAGER", "MASTER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/admin-accounts").hasRole("MASTER")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/admin-accounts/{adminId:[0-9]+}/status").hasRole("MASTER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/members", "/api/v1/admin/members/{memberId:[0-9]+}")
                            .hasAnyRole("MANAGER", "MASTER")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/members/{memberId:[0-9]+}/status")
                            .hasAnyRole("MANAGER", "MASTER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/posts", "/api/v1/admin/projects",
                                "/api/v1/admin/posts/{id:[0-9]+}", "/api/v1/admin/projects/{id:[0-9]+}")
                            .hasAnyRole("MANAGER", "MASTER")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/admin/posts/{id:[0-9]+}/block",
                                "/api/v1/admin/projects/{id:[0-9]+}/block").hasAnyRole("MANAGER", "MASTER")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/admin/posts/{id:[0-9]+}/block",
                                "/api/v1/admin/projects/{id:[0-9]+}/block", "/api/v1/admin/posts/{id:[0-9]+}",
                                "/api/v1/admin/projects/{id:[0-9]+}").hasAnyRole("MANAGER", "MASTER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/members/me", "/api/v1/members/me/posts", "/api/v1/members/me/boards",
                                "/api/v1/members/me/projects").hasRole("USER")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/members/me").hasRole("USER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/categories", "/api/v1/tags").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/members/me/profile").hasRole("USER")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/members/me/profile").hasRole("USER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/posts/search").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/projects", "/api/v1/projects/search", "/api/v1/projects/{projectId:[0-9]+}",
                                "/api/v1/projects/{projectId:[0-9]+}/posts", "/api/v1/members/{memberId:[0-9]+}/projects").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/posts", "/api/v1/posts/{postId:[0-9]+}",
                                "/api/v1/blogs/{handle}/posts", "/api/v1/blogs/{handle}/posts/{postKey}").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/members/{memberId:[0-9]+}/posts").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/members/{memberId:[0-9]+}/boards",
                                "/api/v1/members/{memberId:[0-9]+}/boards/{boardId:[0-9]+}/posts").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/boards").hasRole("USER")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/boards/{boardId:[0-9]+}").hasRole("USER")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/boards/{boardId:[0-9]+}").hasRole("USER")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/posts/{postId:[0-9]+}/thumbnail").hasRole("USER")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/posts/{postId:[0-9]+}/thumbnail").hasRole("USER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/projects/{projectId:[0-9]+}/media").hasRole("USER")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/projects/{projectId:[0-9]+}/media/{mediaId:[0-9]+}").hasRole("USER")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/projects/{projectId:[0-9]+}/media/{mediaId:[0-9]+}").hasRole("USER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/posts").hasRole("USER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/posts/{postId:[0-9]+}/comments",
                                "/api/v1/posts/{postId:[0-9]+}/comments/{commentId:[0-9]+}",
                                "/api/v1/projects/{projectId:[0-9]+}/comments",
                                "/api/v1/projects/{projectId:[0-9]+}/comments/{commentId:[0-9]+}").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/posts/{postId:[0-9]+}/comments",
                                "/api/v1/projects/{projectId:[0-9]+}/comments").hasRole("USER")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/posts/{postId:[0-9]+}/comments/{commentId:[0-9]+}",
                                "/api/v1/projects/{projectId:[0-9]+}/comments/{commentId:[0-9]+}").hasRole("USER")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/posts/{postId:[0-9]+}/comments/{commentId:[0-9]+}",
                                "/api/v1/projects/{projectId:[0-9]+}/comments/{commentId:[0-9]+}").hasRole("USER")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/posts/{postId:[0-9]+}/like",
                                "/api/v1/projects/{projectId:[0-9]+}/like").hasRole("USER")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/posts/{postId:[0-9]+}/like",
                                "/api/v1/projects/{projectId:[0-9]+}/like").hasRole("USER")
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
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(token -> {
                            if (!"USER".equals(token.getClaimAsString("role"))) {
                                boolean active;
                                try {
                                    active = adminSessions.isAccessSessionActive(Long.parseLong(token.getSubject().substring("admin:".length())),
                                            token.getClaimAsString("role"), token.getClaimAsString("sid"));
                                } catch (org.springframework.dao.DataAccessException exception) {
                                    // Redis 또는 계정 저장소 장애로 온라인 검증을 할 수 없으면 인증을 거부한다.
                                    active = false;
                                }
                                if (!active) throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException("invalid_token");
                            }
                            return converter.convert(token);
                        })))
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
        source.registerCorsConfiguration("/api/v1/admin/auth/login", cors);
        source.registerCorsConfiguration("/api/v1/admin/auth/token/refresh", cors);
        source.registerCorsConfiguration("/api/v1/admin/auth/logout", cors);
        CorsConfiguration adminAccountsCors = new CorsConfiguration(cors);
        adminAccountsCors.setAllowedMethods(List.of("GET", "POST"));
        source.registerCorsConfiguration("/api/v1/admin/admin-accounts", adminAccountsCors);
        CorsConfiguration adminStatusCors = new CorsConfiguration(cors);
        adminStatusCors.setAllowedMethods(List.of("PATCH"));
        source.registerCorsConfiguration("/api/v1/admin/admin-accounts/{adminId:[0-9]+}/status", adminStatusCors);
        source.registerCorsConfiguration("/api/v1/admin/members/{memberId:[0-9]+}/status", adminStatusCors);
        CorsConfiguration getCors = new CorsConfiguration(cors);
        getCors.setAllowedMethods(List.of("GET"));
        CorsConfiguration memberMeCors = new CorsConfiguration(cors);
        memberMeCors.setAllowedMethods(List.of("GET", "DELETE"));
        CorsConfiguration adminClassificationCors = new CorsConfiguration(cors);
        adminClassificationCors.setAllowedMethods(List.of("GET", "POST"));
        source.registerCorsConfiguration("/api/v1/admin/categories", adminClassificationCors);
        source.registerCorsConfiguration("/api/v1/admin/tags", adminClassificationCors);
        CorsConfiguration adminClassificationPatchCors = new CorsConfiguration(cors);
        adminClassificationPatchCors.setAllowedMethods(List.of("PATCH"));
        source.registerCorsConfiguration("/api/v1/admin/categories/{id:[0-9]+}", adminClassificationPatchCors);
        source.registerCorsConfiguration("/api/v1/admin/tags/{id:[0-9]+}", adminClassificationPatchCors);
        source.registerCorsConfiguration("/api/v1/admin/post-comments", getCors);
        source.registerCorsConfiguration("/api/v1/admin/project-comments", getCors);
        CorsConfiguration adminCommentDeleteCors = new CorsConfiguration(cors);
        adminCommentDeleteCors.setAllowedMethods(List.of("DELETE"));
        source.registerCorsConfiguration("/api/v1/admin/post-comments/{id:[0-9]+}", adminCommentDeleteCors);
        source.registerCorsConfiguration("/api/v1/admin/project-comments/{id:[0-9]+}", adminCommentDeleteCors);
        source.registerCorsConfiguration("/api/v1/admin/posts", getCors);
        source.registerCorsConfiguration("/api/v1/admin/projects", getCors);
        CorsConfiguration adminContentCors = new CorsConfiguration(cors);
        adminContentCors.setAllowedMethods(List.of("GET", "DELETE"));
        source.registerCorsConfiguration("/api/v1/admin/posts/{id:[0-9]+}", adminContentCors);
        source.registerCorsConfiguration("/api/v1/admin/projects/{id:[0-9]+}", adminContentCors);
        CorsConfiguration adminBlockCors = new CorsConfiguration(cors);
        adminBlockCors.setAllowedMethods(List.of("PUT", "DELETE"));
        source.registerCorsConfiguration("/api/v1/admin/posts/{id:[0-9]+}/block", adminBlockCors);
        source.registerCorsConfiguration("/api/v1/admin/projects/{id:[0-9]+}/block", adminBlockCors);
        source.registerCorsConfiguration("/api/v1/admin/members", getCors);
        source.registerCorsConfiguration("/api/v1/admin/members/{memberId:[0-9]+}", getCors);
        source.registerCorsConfiguration("/api/v1/members/me", memberMeCors);
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
        CorsConfiguration likeCors = new CorsConfiguration(cors);
        likeCors.setAllowedMethods(List.of("PUT", "DELETE"));
        source.registerCorsConfiguration("/api/v1/posts/{postId:[0-9]+}/like", likeCors);
        source.registerCorsConfiguration("/api/v1/projects/{projectId:[0-9]+}/like", likeCors);
        CorsConfiguration commentCollectionCors = new CorsConfiguration(cors);
        commentCollectionCors.setAllowedMethods(List.of("GET", "POST"));
        CorsConfiguration commentDetailCors = new CorsConfiguration(cors);
        commentDetailCors.setAllowedMethods(List.of("GET", "PATCH", "DELETE"));
        source.registerCorsConfiguration("/api/v1/posts/{postId:[0-9]+}/comments", commentCollectionCors);
        source.registerCorsConfiguration("/api/v1/projects/{projectId:[0-9]+}/comments", commentCollectionCors);
        source.registerCorsConfiguration("/api/v1/posts/{postId:[0-9]+}/comments/{commentId:[0-9]+}", commentDetailCors);
        source.registerCorsConfiguration("/api/v1/projects/{projectId:[0-9]+}/comments/{commentId:[0-9]+}", commentDetailCors);
        source.registerCorsConfiguration("/api/v1/projects/search", getCors);
        source.registerCorsConfiguration("/api/v1/projects/{projectId:[0-9]+}/posts", getCors);
        source.registerCorsConfiguration("/api/v1/members/{memberId:[0-9]+}/projects", getCors);
        source.registerCorsConfiguration("/api/v1/members/me/projects", getCors);
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
        CorsConfiguration postMediaCors = new CorsConfiguration(cors);
        postMediaCors.setAllowedMethods(List.of("PUT", "DELETE"));
        source.registerCorsConfiguration("/api/v1/posts/{postId:[0-9]+}/thumbnail", postMediaCors);
        CorsConfiguration projectMediaUploadCors = new CorsConfiguration(cors);
        projectMediaUploadCors.setAllowedMethods(List.of("POST"));
        source.registerCorsConfiguration("/api/v1/projects/{projectId:[0-9]+}/media", projectMediaUploadCors);
        CorsConfiguration projectMediaEditCors = new CorsConfiguration(cors);
        projectMediaEditCors.setAllowedMethods(List.of("PATCH", "DELETE"));
        source.registerCorsConfiguration("/api/v1/projects/{projectId:[0-9]+}/media/{mediaId:[0-9]+}", projectMediaEditCors);
        return source;
    }
}
