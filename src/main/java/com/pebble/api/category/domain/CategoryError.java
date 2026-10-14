package com.pebble.api.category.domain;

import com.pebble.api.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum CategoryError implements ErrorCode {
    INVALID_CATEGORY_SELECTION,
    CATEGORY_HIERARCHY_CONFLICT,
    CATEGORY_SLUG_CONFLICT;

    public HttpStatus status() { return HttpStatus.CONFLICT; }
    public String code() { return name(); }
    public String message() {
        return switch (this) {
            case INVALID_CATEGORY_SELECTION -> "활성 최하위 Category만 새로 연결할 수 있습니다.";
            case CATEGORY_HIERARCHY_CONFLICT -> "Category 깊이·순환·기존 Post 분류 연결을 유지해야 합니다.";
            case CATEGORY_SLUG_CONFLICT -> "이미 사용 중인 Category slug입니다.";
        };
    }
}
