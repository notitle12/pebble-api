package com.pebble.api.global.presentation.response;

import java.util.List;

/** API 계약에서 정의한 표준 오류 응답 형식이다. */
public record ApiErrorResponse(ErrorBody error) {

    public record ErrorBody(String code, String message, List<FieldDetail> details, String traceId) {
        public ErrorBody {
            details = details == null ? List.of() : List.copyOf(details);
        }
    }

    public record FieldDetail(String field, String reason) {
    }
}
