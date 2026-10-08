package com.pebble.api.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.filter.OncePerRequestFilter;

/** 쿠키로 인증하는 변경 요청은 누락·null Origin도 거부한다. */
public class CookieOriginFilter extends OncePerRequestFilter {

    private final List<String> allowedOrigins;
    private final ApiSecurityErrorHandler errorHandler;

    public CookieOriginFilter(CorsProperties properties, ApiSecurityErrorHandler errorHandler) {
        this.allowedOrigins = List.copyOf(properties.allowedOrigins());
        this.errorHandler = errorHandler;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path.isEmpty()) {
            path = request.getRequestURI().substring(request.getContextPath().length());
        }
        return !"POST".equals(request.getMethod())
                || !("/api/v1/auth/token/refresh".equals(path) || "/api/v1/auth/logout".equals(path)
                    || path.matches("^/api/v1/blogs/[^/]+/visits$")
                    || "/api/v1/auth/naver/withdrawal/cancel".equals(path)
                    || "/api/v1/admin/auth/login".equals(path) || "/api/v1/admin/auth/token/refresh".equals(path)
                    || "/api/v1/admin/auth/logout".equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> origins = Collections.list(request.getHeaders(HttpHeaders.ORIGIN));
        if (origins.size() != 1 || !allowedOrigins.contains(origins.getFirst())) {
            errorHandler.handle(request, response, new AccessDeniedException("Invalid cookie request Origin"));
            return;
        }
        chain.doFilter(request, response);
    }
}
