package com.pebble.api.board.domain;

import com.pebble.api.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@RequiredArgsConstructor
public enum BoardError implements ErrorCode {
    BOARD_TREE_CHANGED(HttpStatus.CONFLICT, "다른 작업에서 게시판이 변경되었습니다. 최신 목록을 확인해 주세요."),
    RESOURCE_HAS_CHILDREN(HttpStatus.CONFLICT, "하위 게시판을 먼저 이동하거나 삭제해 주세요.");

    private final HttpStatus status;
    private final String message;
    @Override public HttpStatus status() { return status; }
    @Override public String code() { return name(); }
    @Override public String message() { return message; }
}
