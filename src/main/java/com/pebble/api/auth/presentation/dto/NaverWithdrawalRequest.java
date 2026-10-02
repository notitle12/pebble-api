package com.pebble.api.auth.presentation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;

/** 탈퇴 취소 요청에 사용할 네이버 OAuth 자격 정보다. */
public record NaverWithdrawalRequest(String authorizationCode, String state) {

    public static NaverWithdrawalRequest parse(JsonNode json) {
        if (json == null || !json.isObject()
                || json.size() != 2
                || !json.has("authorizationCode") || !json.has("state")) {
            throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        }
        return new NaverWithdrawalRequest(
                text(json.get("authorizationCode"), 4096),
                text(json.get("state"), 256));
    }

    private static String text(JsonNode node, int maxLength) {
        if (node == null || !node.isTextual()) {
            throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        }
        String value = node.textValue();
        if (value.isBlank() || value.length() > maxLength || !validUnicode(value)) {
            throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        }
        return value;
    }

    private static boolean validUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '\0') return false;
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) return false;
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }
}
