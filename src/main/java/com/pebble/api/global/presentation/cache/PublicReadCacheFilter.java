package com.pebble.api.global.presentation.cache;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/** Spring Security 이후 실행하며 개인 응답과 외부 HTTP 캐시는 분리한다. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class PublicReadCacheFilter extends OncePerRequestFilter {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Pattern PUBLIC_PATH = Pattern.compile("/api/v1/(?:"
            + "posts(?:/popular)?|projects(?:/popular)?|categories|tech-stacks|updates"
            + "|blogs/[a-z][a-z0-9_-]{1,28}[a-z0-9_](?:/home|/links|/posts|/popular-posts)?"
            + "|members/[1-9][0-9]*/(?:boards(?:/[1-9][0-9]*/posts)?|projects))");
    private final PublicReadCache cache;
    private final boolean enabled;

    public PublicReadCacheFilter(PublicReadCache cache, @Value("${pebble.public-read-cache.enabled:true}") boolean enabled) {
        this.cache = cache;
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!eligible(request)) { chain.doFilter(request, response); return; }
        var snapshot = cache.snapshot();
        if (snapshot.isEmpty()) { chain.doFilter(request, response); return; }
        String key = LocalDate.now(SEOUL) + "\n" + snapshot.get() + "\n" + request.getRequestURI()
                + "?" + (request.getQueryString() == null ? "" : request.getQueryString());
        var cached = cache.get(key);
        if (cached != null) {
            response.setContentType(cached.contentType());
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Pebble-Read-Cache", "HIT");
            response.getOutputStream().write(cached.body());
            return;
        }
        var wrapped = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapped);
            String type = wrapped.getContentType();
            if (wrapped.getStatus() == 200 && type != null && type.toLowerCase(Locale.ROOT).startsWith("application/json")
                    && !wrapped.containsHeader("Set-Cookie") && !wrapped.containsHeader("Content-Encoding")) {
                // 조회 도중 커밋이 생기면 이전 가시성의 키에 결과를 넣지 않는다.
                if (cache.snapshot().filter(snapshot.get()::equals).isPresent()) {
                    cache.put(key, wrapped.getContentAsByteArray(), type);
                    wrapped.setHeader("X-Pebble-Read-Cache", "MISS");
                }
            }
        } finally {
            wrapped.copyBodyToResponse();
        }
    }

    private boolean eligible(HttpServletRequest request) throws IOException {
        if (!enabled || !"GET".equals(request.getMethod()) || !PUBLIC_PATH.matcher(request.getRequestURI()).matches()
                || request.getHeader("Authorization") != null || request.getHeader("Cookie") != null
                || request.getUserPrincipal() != null || request.getContentLengthLong() > 0
                || request.getHeader("Transfer-Encoding") != null
                // HTTP/1.x without Content-Length/Transfer-Encoding has no request body.
                // HTTP/2 can carry DATA without a length: only cache after its stream has ended.
                || request.getContentLengthLong() < 0 && !request.getProtocol().startsWith("HTTP/1.")
                    && !request.getInputStream().isFinished()
                || request.getQueryString() != null && request.getQueryString().length() > 2048) return false;
        var accepts = Collections.list(request.getHeaders("Accept"));
        return accepts.isEmpty() || accepts.size() == 1 && Set.of("application/json", "*/*")
                .contains(accepts.get(0).trim().toLowerCase(Locale.ROOT));
    }
}
