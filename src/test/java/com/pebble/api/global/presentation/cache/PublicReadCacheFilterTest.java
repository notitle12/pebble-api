package com.pebble.api.global.presentation.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PublicReadCacheFilterTest {
    private static final String SQL = "select pg_current_snapshot()::text";
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PublicReadCache cache = new PublicReadCache(jdbc);
    private final PublicReadCacheFilter filter = new PublicReadCacheFilter(cache, true);
    private final AtomicInteger calls = new AtomicInteger();
    private final FilterChain origin = (request, response) -> {
        calls.incrementAndGet();
        response.setContentType("application/json");
        response.getOutputStream().write("{\"data\":\"public\"}".getBytes(StandardCharsets.UTF_8));
    };

    private MockHttpServletRequest request(String path) {
        var request = new MockHttpServletRequest("GET", path);
        request.setContent(new byte[0]);
        request.addHeader("Accept", "application/json");
        return request;
    }
    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, origin);
        return response;
    }

    @Test
    void reusesOnlySameSnapshotAndQueryAndRefreshesAfterCommit() throws Exception {
        when(jdbc.queryForObject(SQL, String.class)).thenReturn("10:10:");
        assertThat(run(request("/api/v1/blogs/test-blog")).getHeader("X-Pebble-Read-Cache")).isEqualTo("MISS");
        var hit = run(request("/api/v1/blogs/test-blog"));
        assertThat(hit.getHeader("X-Pebble-Read-Cache")).isEqualTo("HIT");
        assertThat(hit.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(hit.getContentAsString()).contains("public");
        when(jdbc.queryForObject(SQL, String.class)).thenReturn("11:11:");
        run(request("/api/v1/blogs/test-blog"));
        assertThat(calls).hasValue(2);
        var query = request("/api/v1/blogs/test-blog/posts");query.setQueryString("page=1&size=20");run(query);
        query = request("/api/v1/blogs/test-blog/posts");query.setQueryString("page=0&size=20");run(query);
        assertThat(calls).hasValue(4);
    }

    @Test
    void neverCachesCredentialsBodiesOrUnsupportedRepresentations() throws Exception {
        when(jdbc.queryForObject(SQL, String.class)).thenReturn("10:10:");
        run(request("/api/v1/blogs/test-blog"));
        for (String header : new String[]{"Authorization", "Cookie", "Transfer-Encoding"}) {
            var value = request("/api/v1/blogs/test-blog");value.addHeader(header, "test");run(value);
        }
        var body = request("/api/v1/blogs/test-blog");body.setContent(new byte[]{1});run(body);
        var accept = request("/api/v1/blogs/test-blog");accept.removeHeader("Accept");accept.addHeader("Accept", "text/plain");run(accept);
        var principal = request("/api/v1/blogs/test-blog");principal.setUserPrincipal(()->"member");run(principal);
        run(request("/api/v1/members/me/blog/posts/popular"));
        run(request("/api/v1/blogs/test-blog/visits"));
        run(request("/api/v1/blogs/test-blog/posts/private-key"));
        var streaming = request("/api/v1/blogs/test-blog");
        streaming.setProtocol("HTTP/2.0");
        streaming.setContent(null);
        run(streaming);
        assertThat(calls).hasValue(11);
        assertThat(run(request("/api/v1/blogs/test-blog")).getHeader("X-Pebble-Read-Cache")).isEqualTo("HIT");
    }

    @Test
    void doesNotStoreInFlightChangesErrorsOrCookieResponses() throws Exception {
        when(jdbc.queryForObject(SQL, String.class)).thenReturn("10:11:10", "11:11:", "11:11:", "11:11:");
        assertThat(run(request("/api/v1/posts")).getHeader("X-Pebble-Read-Cache")).isNull();
        assertThat(run(request("/api/v1/posts")).getHeader("X-Pebble-Read-Cache")).isEqualTo("MISS");
        var response = new MockHttpServletResponse();
        filter.doFilter(request("/api/v1/categories"), response, (req, res)->{
            ((jakarta.servlet.http.HttpServletResponse)res).setStatus(404);
            res.setContentType("application/json");res.getWriter().write("{\"error\":\"missing\"}");
        });
        assertThat(run(request("/api/v1/categories")).getHeader("X-Pebble-Read-Cache")).isEqualTo("MISS");
        response = new MockHttpServletResponse();
        filter.doFilter(request("/api/v1/updates"), response, (req, res)->{
            ((jakarta.servlet.http.HttpServletResponse)res).setHeader("Set-Cookie", "session=test");
            res.setContentType("application/json");res.getWriter().write("{\"data\":1}");
        });
        assertThat(run(request("/api/v1/updates")).getHeader("X-Pebble-Read-Cache")).isEqualTo("MISS");
    }
}
