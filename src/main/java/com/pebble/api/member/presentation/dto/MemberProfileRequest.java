package com.pebble.api.member.presentation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.member.application.MemberNames;
import java.util.Set;

public record MemberProfileRequest(String blogName, String handle, String nickname, boolean removeProfileImage) {

    public static MemberProfileRequest parse(JsonNode json, boolean initial) {
        if (json == null || !json.isObject()) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        Set<String> allowed = initial ? Set.of("blogName", "handle", "nickname", "removeProfileImage") : Set.of("blogName", "nickname", "removeProfileImage");
        json.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        });
        String blog = initial || json.has("blogName") ? text(json.get("blogName"), 100, "blogName") : null;
        String nickname = json.has("nickname") ? text(json.get("nickname"), 30, "nickname") : null;
        String handle = null;
        if (initial) {
            handle = MemberNames.normalizeHandle(text(json.get("handle"), 30, "handle"));
        }
        if (json.has("removeProfileImage") && !json.get("removeProfileImage").isBoolean()) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        return new MemberProfileRequest(blog, handle, nickname, json.path("removeProfileImage").asBoolean(false));
    }

    private static String text(JsonNode json, int limit, String field) {
        if (json == null || !json.isTextual()) throw new ApplicationException(GlobalErrorCode.VALIDATION_ERROR, field, "문자열을 입력해 주세요.");
        return MemberNames.normalize(json.textValue(), limit, field);
    }
}
