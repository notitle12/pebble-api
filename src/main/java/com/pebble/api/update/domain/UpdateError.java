package com.pebble.api.update.domain;
import com.pebble.api.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;
public enum UpdateError implements ErrorCode {
    UPDATE_CONFLICT;
    public HttpStatus status() { return HttpStatus.CONFLICT; }
    public String code() { return name(); }
    public String message() { return "다른 변경이 반영되었습니다. 최신 내용을 불러온 뒤 다시 수정해 주세요."; }
}
