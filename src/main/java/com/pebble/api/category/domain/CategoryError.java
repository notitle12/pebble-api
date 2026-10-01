package com.pebble.api.category.domain;

import com.pebble.api.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum CategoryError implements ErrorCode {
    INVALID_CATEGORY_SELECTION;

    public HttpStatus status() { return HttpStatus.CONFLICT; }
    public String code() { return name(); }
    public String message() { return "활성 최하위 Category만 새로 연결할 수 있습니다."; }
}
