package com.pebble.api.global.security;

import com.pebble.api.auth.application.AdminSessionService;
import org.springframework.web.filter.CorsFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiSecurityErrorHandler errorHandler, CorsProperties corsProperties,
                                            AdminSessionService adminSessions, DocumentationAccess documentationAccess) throws Exception {
        JwtAuthenticationConverter converter = userJwtAuthenticationConverter();
        return http
                .cors(Customizer.withDefaults())
                .addFilterBefore(new CookieOriginFilter(corsProperties, errorHandler), CorsFilter.class)
                // OAuth state와 필수 Origin 검증을 적용한 경로만 기본 CSRF 토큰 검사에서 제외한다.
                .csrf(csrf -> csrf.ignoringRequestMatchers(SecurityEndpoints.csrfExemptions()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> SecurityEndpoints.authorize(authorize,
                        documentationAccess.enabled()))
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

}
