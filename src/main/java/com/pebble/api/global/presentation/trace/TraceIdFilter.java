package com.pebble.api.global.presentation.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 각 요청에 traceId를 부여하고 요청 처리 중 로그 MDC에 보관한다. */
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = TraceIdGenerator.generate();
        request.setAttribute(REQUEST_ATTRIBUTE, traceId);
        MDC.put(MDC_KEY, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
