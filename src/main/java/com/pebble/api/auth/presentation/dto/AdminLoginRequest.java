package com.pebble.api.auth.presentation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import java.util.Set;

public record AdminLoginRequest(String loginId, String password) {
    public static AdminLoginRequest parse(JsonNode json) {
        if (json == null || !json.isObject()) throw invalid();
        json.fieldNames().forEachRemaining(field -> { if (!Set.of("loginId", "password").contains(field)) throw invalid(); });
        JsonNode id = json.get("loginId");
        JsonNode secret = json.get("password");
        if (id == null || !id.isTextual() || !id.textValue().matches("[A-Za-z0-9][A-Za-z0-9._-]{2,99}")
                || secret == null || !secret.isTextual() || secret.textValue().isBlank()
                || secret.textValue().codePointCount(0, secret.textValue().length()) > 128) throw invalid();
        String password = secret.textValue();
        for (int i = 0; i < password.length();) {
            int point = password.codePointAt(i);
            if (Character.isISOControl(point) || point >= 0xD800 && point <= 0xDFFF) throw invalid();
            i += Character.charCount(point);
        }
        return new AdminLoginRequest(id.textValue(), password);
    }
    private static ApplicationException invalid() { return new ApplicationException(GlobalErrorCode.INVALID_REQUEST); }
    @Override public String toString() { return "AdminLoginRequest[redacted]"; }
}
