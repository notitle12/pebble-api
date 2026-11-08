package com.pebble.api.post.domain;

import com.pebble.api.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum PostError implements ErrorCode {
    CONTENT_DELETED,
    POST_ORDER_CONFLICT,
    SERIES_ORDER_CONFLICT,
    SERIES_LIMIT;

    public HttpStatus status() { return HttpStatus.CONFLICT; }
    public String code() { return name(); }
    public String message() {
        if (this == SERIES_LIMIT) return "시리즈는 최대 100개까지 만들 수 있습니다.";
        if (this == SERIES_ORDER_CONFLICT) return "시리즈 목록이나 순서가 변경됐어요. 목록을 다시 불러온 뒤 저장해 주세요.";
        return this == POST_ORDER_CONFLICT ? "관련 게시글 목록이나 순서가 변경됐어요. 목록을 다시 불러온 뒤 저장해 주세요."
                : "삭제된 콘텐츠는 변경하거나 복원할 수 없습니다.";
    }
}
