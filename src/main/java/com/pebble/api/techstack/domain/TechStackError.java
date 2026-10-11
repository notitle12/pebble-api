package com.pebble.api.techstack.domain;

import com.pebble.api.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum TechStackError implements ErrorCode {
    INACTIVE_TECH_STACK,
    TECH_STACK_SLUG_CONFLICT;

    public HttpStatus status() { return HttpStatus.CONFLICT; }
    public String code() { return name(); }
    public String message() {
        return this == INACTIVE_TECH_STACK ? "비활성 기술 스택는 새로 연결할 수 없습니다." : "이미 사용 중인 기술 스택 slug입니다.";
    }
}
