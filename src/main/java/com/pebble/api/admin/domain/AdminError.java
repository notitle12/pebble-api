package com.pebble.api.admin.domain;

import com.pebble.api.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@RequiredArgsConstructor
public enum AdminError implements ErrorCode {
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "이미 사용 중인 관리자 로그인 ID입니다.");
    private final HttpStatus status;
    private final String message;
    @Override public HttpStatus status() { return status; }
    @Override public String code() { return name(); }
    @Override public String message() { return message; }
}
