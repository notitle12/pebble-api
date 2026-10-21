package com.pebble.api.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;

class RetiredGuestCommentsTest {
    @Test
    void retiredGuestEndpointsHaveNoAuthorizationCorsOrCsrfExemption() {
        var cors = new CorsConfiguration().corsConfigurationSource(new CorsProperties(List.of("https://www.pebble-log.com")));
        assertThat(SecurityEndpoints.API).noneMatch(endpoint -> endpoint.path().contains("/guest"));
        for (var resource : List.of("posts", "projects")) {
            for (var suffix : List.of("guest", "2/guest/read", "2/guest/update", "2/guest/delete")) {
                var request = new MockHttpServletRequest("POST", "/api/v1/" + resource + "/1/comments/" + suffix);
                assertThat(cors.getCorsConfiguration(request)).isNull();
                for (var matcher : SecurityEndpoints.csrfExemptions()) assertThat(matcher.matches(request)).isFalse();
            }
            assertThat(SecurityEndpoints.API).anyMatch(endpoint -> endpoint.method() == HttpMethod.POST
                    && endpoint.path().startsWith("/api/v1/" + resource + "/")
                    && endpoint.path().endsWith("/comments") && endpoint.access() == SecurityEndpoints.Access.USER);
            assertThat(SecurityEndpoints.API).anyMatch(endpoint -> endpoint.method() == HttpMethod.GET
                    && endpoint.path().startsWith("/api/v1/" + resource + "/")
                    && endpoint.path().endsWith("/comments") && endpoint.access() == SecurityEndpoints.Access.PUBLIC);
        }
    }
}
