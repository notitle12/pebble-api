package com.pebble.api.global.presentation;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.ErrorCode;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.global.presentation.response.ApiErrorResponse;
import com.pebble.api.global.presentation.response.ApiErrorResponse.ErrorBody;
import com.pebble.api.global.presentation.response.ApiErrorResponse.FieldDetail;
import com.pebble.api.global.presentation.trace.TraceIdFilter;
import com.pebble.api.global.presentation.trace.TraceIdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** API 요청 예외를 공통 오류 응답으로 변환한다. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ApiErrorResponse> handleApplicationException(
            ApplicationException exception, HttpServletRequest request) {
        return error(exception.error(), exception.violations().stream()
                .map(violation -> new FieldDetail(violation.field(), violation.reason())).toList(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<FieldDetail> details = exception.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldDetail)
                .toList();
        return error(GlobalErrorCode.VALIDATION_ERROR, details, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableRequest(HttpServletRequest request) {
        return error(GlobalErrorCode.INVALID_REQUEST, List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(HttpServletRequest request) {
        return error(GlobalErrorCode.INTERNAL_ERROR, List.of(), request);
    }

    private FieldDetail toFieldDetail(FieldError fieldError) {
        return new FieldDetail(fieldError.getField(), fieldError.getDefaultMessage());
    }

    private ResponseEntity<ApiErrorResponse> error(
            ErrorCode error, List<FieldDetail> details, HttpServletRequest request) {
        Object requestTraceId = request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE);
        String traceId = requestTraceId instanceof String value ? value : TraceIdGenerator.generate();
        return ResponseEntity.status(error.status())
                .body(new ApiErrorResponse(new ErrorBody(error.code(), error.message(), details, traceId)));
    }
}
