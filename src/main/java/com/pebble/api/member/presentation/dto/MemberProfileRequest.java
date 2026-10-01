package com.pebble.api.member.presentation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.member.application.MemberNames;
import java.util.Locale;
import java.util.Set;

public record MemberProfileRequest(String blogName, String handle, String nickname) {
    private static final Set<String> RESERVED = Set.of("admin", "api", "auth", "me", "posts", "search", "settings", "www");

    public static MemberProfileRequest parse(JsonNode json, boolean initial) {
        if (json == null || !json.isObject()) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        Set<String> allowed = initial ? Set.of("blogName", "handle", "nickname") : Set.of("blogName", "nickname");
        json.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        });
        String blog = initial || json.has("blogName") ? text(json.get("blogName"), 100, "blogName") : null;
        String nickname = json.has("nickname") ? text(json.get("nickname"), 30, "nickname") : null;
        String handle = null;
        if (initial) {
            handle = text(json.get("handle"), 30, "handle").toLowerCase(Locale.ROOT);
            if (!handle.matches("[a-z][a-z0-9-]{1,28}[a-z0-9]") || RESERVED.contains(handle)) {
                throw new ApplicationException(GlobalErrorCode.VALIDATION_ERROR, "handle", "영문으로 시작하는 3~30자의 영문·숫자·하이픈을 사용해 주세요. 예약어와 끝 하이픈은 사용할 수 없습니다.");
            }
        }
        return new MemberProfileRequest(blog, handle, nickname);
    }

    private static String text(JsonNode json, int limit, String field) {
        if (json == null || !json.isTextual()) throw new ApplicationException(GlobalErrorCode.VALIDATION_ERROR, field, "문자열을 입력해 주세요.");
        return MemberNames.normalize(json.textValue(), limit, field);
    }
}
