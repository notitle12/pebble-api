package com.pebble.api.project.domain;

import com.pebble.api.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum ProjectError implements ErrorCode {
    CONTENT_DELETED;

    public HttpStatus status() { return HttpStatus.CONFLICT; }
    public String code() { return name(); }
    public String message() { return "삭제된 콘텐츠는 변경하거나 복원할 수 없습니다."; }
}
