package com.pebble.api.tag.domain;

import com.pebble.api.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum TagError implements ErrorCode {
    INACTIVE_TAG;

    public HttpStatus status() { return HttpStatus.CONFLICT; }
    public String code() { return name(); }
    public String message() { return "비활성 Tag는 새로 연결할 수 없습니다."; }
}
