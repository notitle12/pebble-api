package com.pebble.api.post.presentation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.post.application.PostSeriesChanges;
import java.util.Set;

public final class PostSeriesWriteRequest {
    private PostSeriesWriteRequest() { }
    public static PostSeriesChanges parse(JsonNode json, boolean create) {
        if (json == null || !json.isObject() || json.isEmpty()) throw invalid("series");
        json.fieldNames().forEachRemaining(field -> { if (!Set.of("name", "description").contains(field)) throw invalid(field); });
        String name = create || json.has("name") ? text(json.get("name"), "name", 100, true) : null;
        String description = json.has("description") ? text(json.get("description"), "description", 1000, false) : create ? "" : null;
        return new PostSeriesChanges(name, description);
    }
    private static String text(JsonNode json, String field, int max, boolean required) {
        if (json == null || !json.isTextual()) throw invalid(field);
        String value = json.textValue();
        if (required) value = value.strip();
        if (value.codePointCount(0, value.length()) > max || required && value.isBlank()) throw invalid(field);
        for (int offset = 0; offset < value.length();) {
            int point = value.codePointAt(offset);
            if (point == 0 || point >= 0xD800 && point <= 0xDFFF || required && Character.isISOControl(point)) throw invalid(field);
            offset += Character.charCount(point);
        }
        return value;
    }
    private static ApplicationException invalid(String field) {
        return new ApplicationException(GlobalErrorCode.VALIDATION_ERROR, field, "시리즈 이름은 1~100자, 소개는 1000자 이내로 입력해 주세요.");
    }
}
