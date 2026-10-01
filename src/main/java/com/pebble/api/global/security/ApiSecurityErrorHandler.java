package com.pebble.api.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.global.presentation.response.ApiErrorResponse.ErrorBody;
import com.pebble.api.global.presentation.response.ApiErrorResponse;
import com.pebble.api.global.presentation.trace.TraceIdFilter;
import com.pebble.api.global.presentation.trace.TraceIdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public class ApiSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public ApiSecurityErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        boolean hasToken = request.getHeader(HttpHeaders.AUTHORIZATION) != null;
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(request, response, 401, hasToken ? "INVALID_TOKEN" : "AUTHENTICATION_REQUIRED",
                hasToken ? "토큰이 유효하지 않습니다." : "로그인이 필요합니다.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        write(request, response, 403, "INSUFFICIENT_ROLE", "요청을 처리할 권한이 없습니다.");
    }

    private void write(HttpServletRequest request, HttpServletResponse response,
                       int status, String code, String message) throws IOException {
        Object trace = request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE);
        String traceId = trace instanceof String value ? value : TraceIdGenerator.generate();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                new ApiErrorResponse(new ErrorBody(code, message, List.of(), traceId)));
    }
}
