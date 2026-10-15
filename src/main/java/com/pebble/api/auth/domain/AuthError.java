package com.pebble.api.auth.domain;

import com.pebble.api.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AuthError implements ErrorCode {
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
    REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "갱신 토큰이 유효하지 않습니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "갱신 토큰이 유효하지 않습니다."),
    INVALID_OAUTH_STATE(HttpStatus.UNAUTHORIZED, "INVALID_OAUTH_STATE", "로그인 요청을 확인할 수 없습니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "로그인 정보가 유효하지 않습니다."),
    AGE_RESTRICTED(HttpStatus.FORBIDDEN, "AGE_RESTRICTED", "Pebble은 만 14세 이상만 가입하고 이용할 수 있습니다."),
    AGE_VERIFICATION_REQUIRED(HttpStatus.FORBIDDEN, "AGE_VERIFICATION_REQUIRED", "네이버 출생연도와 생일 정보로 연령을 확인할 수 없습니다. 정보 제공 동의를 확인한 뒤 다시 로그인해 주세요."),
    ACCOUNT_SUSPENDED(HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED", "정지된 계정은 로그인할 수 없습니다."),
    WITHDRAWAL_PENDING(HttpStatus.CONFLICT, "WITHDRAWAL_PENDING", "탈퇴 예약이 진행 중인 계정은 로그인할 수 없습니다."),
    OAUTH_PROVIDER_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "OAUTH_PROVIDER_UNAVAILABLE", "외부 인증 서비스를 사용할 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    AuthError(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
