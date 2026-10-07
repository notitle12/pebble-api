package com.pebble.api.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class CorsConfigurationTest {
    @Test
    void corsUsesEveryRegisteredMethodAndRejectsOtherOrigins() {
        var source = new CorsConfiguration().corsConfigurationSource(new CorsProperties(List.of("https://www.pebble-log.com")));
        for (var endpoint : SecurityEndpoints.API) {
            var path = endpoint.path().replaceAll("\\{[^}]+}", "1");
            var cors = source.getCorsConfiguration(new MockHttpServletRequest(endpoint.method().name(), path));
            assertThat(cors).as(path).isNotNull();
            assertThat(cors.checkHttpMethod(endpoint.method())).as(path).isNotNull();
            assertThat(cors.checkOrigin("https://www.pebble-log.com")).isNotNull();
            assertThat(cors.checkOrigin("https://untrusted.example")).isNull();
            assertThat(cors.getAllowCredentials()).isTrue();
        }
        assertThat(source.getCorsConfiguration(new MockHttpServletRequest("POST", "/api/v1/auth/naver/unregistered"))).isNull();
        assertThat(source.getCorsConfiguration(new MockHttpServletRequest("GET", "/v3/api-docs"))).isNull();
    }

    @Test
    void credentialedCorsRequiresExactOrigin() {
        for (var origin : List.of("*", "https://*.example.com", "https://example.com/path", "https://user@example.com")) {
            assertThatThrownBy(() -> new CorsConfiguration().corsConfigurationSource(new CorsProperties(List.of(origin))))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
