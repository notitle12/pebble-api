package com.pebble.api.global.presentation.response;

/** API 계약에서 정의한 표준 성공 응답 래퍼다. */
public record ApiResponse<T>(T data) {

    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data);
    }
}
