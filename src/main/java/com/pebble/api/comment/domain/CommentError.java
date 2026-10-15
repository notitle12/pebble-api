package com.pebble.api.comment.domain;

import com.pebble.api.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum CommentError implements ErrorCode {
    INVALID_COMMENT_PASSWORD(HttpStatus.FORBIDDEN, "댓글 비밀번호가 올바르지 않습니다."),
    COMMENT_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "댓글 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
    COMMENT_SECURITY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "댓글 보안 확인을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    private final HttpStatus status;
    private final String message;
    CommentError(HttpStatus status, String message) { this.status=status; this.message=message; }
    public HttpStatus status(){return status;}
    public String code(){return name();}
    public String message(){return message;}
}
