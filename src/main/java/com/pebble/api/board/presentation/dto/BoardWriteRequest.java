package com.pebble.api.board.presentation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.board.application.BoardChanges;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import java.util.HashSet;
import java.util.Set;

/** Board 요청 JSON을 검증해 업무 입력으로 변환한다. */
public final class BoardWriteRequest {
    private static final Set<String> FIELDS = Set.of("name", "parentId", "displayOrder");

    private BoardWriteRequest() {
    }

    public static BoardChanges parse(JsonNode json, boolean create) {
        if (json == null || !json.isObject()) {
            throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        }
        json.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        });
        if (create && !json.has("name")) {
            throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        }

        Set<String> supplied = new HashSet<>();
        json.fieldNames().forEachRemaining(supplied::add);
        String name = json.has("name") ? text(json.get("name"), "name") : null;
        Long parentId = null;
        if (json.has("parentId") && !json.get("parentId").isNull()) {
            parentId = id(json.get("parentId"), "parentId");
        }
        Integer displayOrder = json.has("displayOrder") ? Integer.valueOf(order(json.get("displayOrder"))) : (create ? Integer.valueOf(0) : null);
        return new BoardChanges(Set.copyOf(supplied), name, parentId, displayOrder);
    }

    public static long id(String value, String field) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) fail(field, "양의 10진 문자열 ID를 입력해 주세요.");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            fail(field, "ID 범위를 확인해 주세요.");
            throw new AssertionError();
        }
    }

    private static Long id(JsonNode value, String field) {
        if (!value.isTextual()) fail(field, "ID는 문자열로 입력해 주세요.");
        return id(value.textValue(), field);
    }

    private static String text(JsonNode value, String field) {
        if (value == null || !value.isTextual()) fail(field, "문자열을 입력해 주세요.");
        String text = value.textValue();
        for (int offset = 0; offset < text.length();) {
            int point = text.codePointAt(offset);
            if (point == 0 || (point >= 0xD800 && point <= 0xDFFF)) fail(field, "올바른 유니코드 문자열을 입력해 주세요.");
            offset += Character.charCount(point);
        }
        if (text.isBlank()) fail(field, "비어 있지 않은 값을 입력해 주세요.");
        if (text.codePointCount(0, text.length()) > 50) fail(field, "최대 50자까지 입력할 수 있습니다.");
        return text;
    }

    private static int order(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            fail("displayOrder", "0 이상의 정수를 입력해 주세요.");
        }
        return value.intValue();
    }

    private static void fail(String field, String reason) {
        throw new ApplicationException(GlobalErrorCode.VALIDATION_ERROR, field, reason);
    }
}
