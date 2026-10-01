package com.pebble.api.member.domain;

import com.pebble.api.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@RequiredArgsConstructor
public enum MemberError implements ErrorCode {
    ACCOUNT_SUSPENDED(HttpStatus.FORBIDDEN, "정지된 계정은 이용할 수 없습니다."),
    ACCOUNT_WITHDRAWAL_PENDING(HttpStatus.FORBIDDEN, "탈퇴 대기 중인 계정은 이용할 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public String message() {
        return message;
    }
}
