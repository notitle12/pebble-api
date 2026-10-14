package com.pebble.api.global.exception;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@RequiredArgsConstructor
public enum GlobalErrorCode implements ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "요청 값을 확인해 주세요."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 본문을 확인해 주세요."),
    INVALID_MEDIA(HttpStatus.BAD_REQUEST, "이미지를 처리할 수 없습니다."),
    MEDIA_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "이미지 크기 한도를 초과했습니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 이미지 형식입니다."),
    STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "이미지 저장소를 사용할 수 없습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "요청을 처리하지 못했습니다."),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "토큰이 유효하지 않습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    INSUFFICIENT_ROLE(HttpStatus.FORBIDDEN, "요청을 처리할 권한이 없습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public String message() {
        return message;
    }
}
