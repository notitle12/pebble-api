package com.pebble.api.auth.presentation;

import com.pebble.api.auth.domain.AuthException;
import com.pebble.api.global.presentation.response.ApiErrorResponse.ErrorBody;
import com.pebble.api.global.presentation.response.ApiErrorResponse;
import com.pebble.api.global.presentation.trace.TraceIdFilter;
import com.pebble.api.global.presentation.trace.TraceIdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.pebble.api.auth.presentation")
public class AuthExceptionHandler {

    @ExceptionHandler(AuthException.class)
    ResponseEntity<ApiErrorResponse> handleAuthException(AuthException exception, HttpServletRequest request) {
        Object trace = request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE);
        String traceId = trace instanceof String value ? value : TraceIdGenerator.generate();
        return ResponseEntity.status(exception.error().status())
                .body(new ApiErrorResponse(new ErrorBody(exception.error().code(),
                        exception.error().message(), List.of(), traceId)));
    }
}
