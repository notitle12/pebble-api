package com.pebble.api.comment.presentation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.comment.application.CommentChanges;
import com.pebble.api.comment.domain.CommentVisibility;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import java.util.Set;

public final class CommentWriteRequest {
    private CommentWriteRequest() { }

    public static CommentChanges parse(JsonNode json, boolean create) {
        if (json == null || !json.isObject()) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        json.fieldNames().forEachRemaining(field -> {
            if (!Set.of("body", "visibility").contains(field)) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        });
        String body = null;
        if (create || json.has("body")) {
            JsonNode node = json.get("body");
            if (node == null || !node.isTextual()) throw invalid("body");
            body = node.textValue();
            if (body.isBlank() || body.codePointCount(0, body.length()) > 2000) throw invalid("body");
            for (int offset = 0; offset < body.length();) {
                int point = body.codePointAt(offset);
                if (point == 0 || (point >= 0xD800 && point <= 0xDFFF)) throw invalid("body");
                offset += Character.charCount(point);
            }
        }
        CommentVisibility visibility = null;
        if (create || json.has("visibility")) {
            JsonNode node = json.get("visibility");
            if (node == null || !node.isTextual()) throw invalid("visibility");
            try { visibility = CommentVisibility.valueOf(node.textValue()); }
            catch (IllegalArgumentException exception) { throw invalid("visibility"); }
        }
        return new CommentChanges(body, visibility, json.has("body"), json.has("visibility"));
    }

    public static long id(String value) {
        try {
            if (!value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException exception) { throw invalid("id"); }
    }
    private static ApplicationException invalid(String field) {
        return new ApplicationException(GlobalErrorCode.VALIDATION_ERROR, field, "지원되는 요청 값을 입력해 주세요.");
    }
}
