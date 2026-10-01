package com.pebble.api.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.global.exception.ErrorCode;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.global.presentation.response.ApiErrorResponse.ErrorBody;
import com.pebble.api.global.presentation.response.ApiErrorResponse;
import com.pebble.api.global.presentation.trace.TraceIdFilter;
import com.pebble.api.global.presentation.trace.TraceIdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ApiSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        boolean hasToken = request.getHeader(HttpHeaders.AUTHORIZATION) != null;
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(request, response, hasToken ? GlobalErrorCode.INVALID_TOKEN : GlobalErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        write(request, response, GlobalErrorCode.INSUFFICIENT_ROLE);
    }

    private void write(HttpServletRequest request, HttpServletResponse response,
                       ErrorCode error) throws IOException {
        Object trace = request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE);
        String traceId = trace instanceof String value ? value : TraceIdGenerator.generate();
        response.setStatus(error.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                new ApiErrorResponse(new ErrorBody(error.code(), error.message(), List.of(), traceId)));
    }
}
