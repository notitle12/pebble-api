package com.pebble.api.global.presentation;

import com.pebble.api.global.presentation.response.ApiErrorResponse;
import com.pebble.api.global.presentation.response.ApiErrorResponse.ErrorBody;
import com.pebble.api.global.presentation.response.ApiErrorResponse.FieldDetail;
import com.pebble.api.global.presentation.trace.TraceIdFilter;
import com.pebble.api.global.presentation.trace.TraceIdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** API 요청 예외를 공통 오류 응답으로 변환한다. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<FieldDetail> details = exception.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldDetail)
                .toList();
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "요청 값을 확인해 주세요.", details, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableRequest(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 본문을 확인해 주세요.", List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(HttpServletRequest request) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "요청을 처리하지 못했습니다.", List.of(), request);
    }

    private FieldDetail toFieldDetail(FieldError fieldError) {
        return new FieldDetail(fieldError.getField(), fieldError.getDefaultMessage());
    }

    private ResponseEntity<ApiErrorResponse> error(
            HttpStatus status, String code, String message, List<FieldDetail> details, HttpServletRequest request) {
        Object requestTraceId = request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE);
        String traceId = requestTraceId instanceof String value ? value : TraceIdGenerator.generate();
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(new ErrorBody(code, message, details, traceId)));
    }
}
