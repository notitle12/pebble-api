package com.pebble.api.project.domain;

import com.pebble.api.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum ProjectError implements ErrorCode {
    CONTENT_DELETED, THUMBNAIL_ALREADY_EXISTS;

    public HttpStatus status() { return HttpStatus.CONFLICT; }
    public String code() { return name(); }
    public String message() { return this == CONTENT_DELETED ? "삭제된 콘텐츠는 변경하거나 복원할 수 없습니다." : "대표 이미지는 하나만 등록할 수 있습니다."; }
}
