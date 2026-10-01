package com.pebble.api.post.presentation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.post.application.PostChanges;
import com.pebble.api.post.application.PostChanges.BlockInput;
import com.pebble.api.post.domain.BlockType;
import com.pebble.api.post.domain.CodeLanguage;
import com.pebble.api.post.domain.PostVisibility;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;

/** JSON 필드 존재 여부와 형식을 검증하고 업무 입력으로 변환한다. */
public final class PostWriteRequest {
    private static final Set<String> FIELDS = Set.of("title", "summary", "blocks", "categoryId",
            "tagIds", "boardId", "projectId", "visibilityStatus", "slug", "displayOrder");

    private PostWriteRequest() {
    }

    public static PostChanges parse(JsonNode json, boolean create) {
        object(json, FIELDS);
        if (!create && json.has("slug")) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        Set<String> supplied = new HashSet<>();
        json.fieldNames().forEachRemaining(supplied::add);
        for (String unsupported : List.of("projectId")) {
            if (json.hasNonNull(unsupported)) {
                throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
            }
        }
        String title = null;
        if (create || json.has("title")) {
            title = text(json.get("title"), "title", 200, false, true);
        }
        String summary = json.has("summary") ? text(json.get("summary"), "summary", 500, true, false) : null;
        Long categoryId = json.hasNonNull("categoryId") ? id(json.get("categoryId"), "categoryId") : null;
        Long boardId = json.hasNonNull("boardId") ? id(json.get("boardId"), "boardId") : null;
        List<Long> tags = null;
        if (json.has("tagIds")) {
            JsonNode node = json.get("tagIds");
            if (!node.isArray()) fail("tagIds", "ID 배열을 입력해 주세요.");
            tags = new ArrayList<>();
            Set<Long> distinct = new HashSet<>();
            for (JsonNode tag : node) {
                long value = id(tag, "tagIds");
                if (!distinct.add(value)) fail("tagIds", "중복된 Tag를 지정할 수 없습니다.");
                tags.add(value);
            }
            tags = List.copyOf(tags);
        } else if (create) {
            tags = List.of();
        }
        List<BlockInput> blocks = null;
        if (create || json.has("blocks")) {
            JsonNode node = json.get("blocks");
            if (node == null || !node.isArray() || node.isEmpty()) fail("blocks", "본문 블록을 한 개 이상 입력해 주세요.");
            List<BlockInput> parsed = new ArrayList<>();
            int index = 0;
            for (JsonNode block : node) {
                object(block, Set.of("type", "content", "language", "title"));
                String prefix = "blocks[" + index++ + "].";
                BlockType type = enumValue(block.get("type"), BlockType.class, prefix + "type");
                String content = text(block.get("content"), prefix + "content", 50000, false, false);
                CodeLanguage language = block.hasNonNull("language")
                        ? enumValue(block.get("language"), CodeLanguage.class, prefix + "language") : null;
                if (type == BlockType.CODE && language == null) fail(prefix + "language", "코드 언어를 지정해 주세요.");
                String blockTitle = block.has("title") ? text(block.get("title"), prefix + "title", 100, true, false) : null;
                parsed.add(new BlockInput(type, content, language, blockTitle));
            }
            blocks = List.copyOf(parsed);
        }
        PostVisibility visibility = null;
        if (create || json.has("visibilityStatus")) {
            visibility = enumValue(json.get("visibilityStatus"), PostVisibility.class, "visibilityStatus");
            if (visibility == PostVisibility.DELETED) fail("visibilityStatus", "PUBLIC 또는 HIDDEN을 지정해 주세요.");
        }
        String slug = json.hasNonNull("slug") ? normalizeSlug(text(json.get("slug"), "slug", 200, false, true)) : null;
        Integer order = null;
        if (json.has("displayOrder")) {
            JsonNode node = json.get("displayOrder");
            if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 0) fail("displayOrder", "0 이상의 정수 위치를 입력해 주세요.");
            order = node.intValue();
        }
        return new PostChanges(Set.copyOf(supplied), title, summary, categoryId, tags, blocks, visibility, slug, order, boardId);
    }

    public static long id(String value, String field) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            fail(field, "양의 10진 문자열 ID를 입력해 주세요.");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            fail(field, "ID 범위를 확인해 주세요.");
            throw new AssertionError();
        }
    }

    public static String normalizeSlug(String value) {
        String slug = value.toLowerCase(Locale.ROOT);
        if (value.length() > 200 || !value.matches("[A-Za-z0-9]+(-[A-Za-z0-9]+)*")
                || slug.matches("[0-9]+") || slug.equals("search")) {
            fail("slug", "영문·숫자·단어 사이 하이픈을 사용해 주세요. 숫자 전용 값과 예약어 search는 사용할 수 없습니다.");
        }
        return slug;
    }

    private static long id(JsonNode value, String field) {
        if (!value.isTextual()) fail(field, "ID는 문자열로 입력해 주세요.");
        return id(value.textValue(), field);
    }

    private static void object(JsonNode json, Set<String> fields) {
        if (json == null || !json.isObject()) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        json.fieldNames().forEachRemaining(field -> {
            if (!fields.contains(field)) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        });
    }

    private static String text(JsonNode json, String field, int max, boolean nullable, boolean nonBlank) {
        if (nullable && json != null && json.isNull()) return null;
        if (json == null || !json.isTextual()) fail(field, "문자열을 입력해 주세요.");
        String value = json.textValue();
        for (int offset = 0; offset < value.length();) {
            int point = value.codePointAt(offset);
            if (point == 0 || (point >= 0xD800 && point <= 0xDFFF)) fail(field, "올바른 유니코드 문자열을 입력해 주세요.");
            offset += Character.charCount(point);
        }
        // PostgreSQL char_length와 동일하게 유니코드 코드 포인트 수를 센다.
        if (value.codePointCount(0, value.length()) > max) fail(field, "최대 " + max + "자까지 입력할 수 있습니다.");
        if (nonBlank && value.isBlank()) fail(field, "비어 있지 않은 값을 입력해 주세요.");
        return value;
    }

    private static <E extends Enum<E>> E enumValue(JsonNode json, Class<E> type, String field) {
        if (json == null || !json.isTextual()) fail(field, "허용된 값을 입력해 주세요.");
        try {
            return Enum.valueOf(type, json.textValue());
        } catch (IllegalArgumentException exception) {
            fail(field, "허용된 값을 입력해 주세요.");
            throw new AssertionError();
        }
    }

    private static void fail(String field, String reason) {
        throw new ApplicationException(GlobalErrorCode.VALIDATION_ERROR, field, reason);
    }
}
