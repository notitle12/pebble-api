package com.pebble.api.admin.presentation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.category.application.CategoryChanges;
import com.pebble.api.category.domain.CategoryStatus;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.tag.domain.TagStatus;
import com.pebble.api.tag.application.TagChanges;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** 관리자 분류 요청 JSON을 검증해 업무 입력으로 변환한다. */
public final class AdminClassificationWriteRequest {
    private static final Set<String> CATEGORY_FIELDS = Set.of("name", "slug", "parentId", "displayOrder", "status");
    private static final Set<String> TAG_FIELDS = Set.of("name", "slug", "displayOrder", "status");

    private AdminClassificationWriteRequest() { }

    public static CategoryChanges category(JsonNode json, boolean create) {
        object(json);
        fields(json, CATEGORY_FIELDS);
        requiredOnCreate(json, create, "name", "slug");
        if (json.has("status") && create) throw invalid();
        Set<String> supplied = names(json);
        String name = json.has("name") ? name(json.get("name")) : null;
        String slug = json.has("slug") ? slug(json.get("slug")) : null;
        Long parentId = null;
        if (json.has("parentId") && !json.get("parentId").isNull()) parentId = id(json.get("parentId"));
        Integer order = json.has("displayOrder") ? Integer.valueOf(order(json.get("displayOrder"))) : create ? Integer.valueOf(0) : null;
        CategoryStatus status = json.has("status") ? categoryStatus(json.get("status")) : null;
        return new CategoryChanges(Set.copyOf(supplied), name, slug, parentId, order, status);
    }

    public static TagChanges tag(JsonNode json, boolean create) {
        object(json);
        fields(json, TAG_FIELDS);
        requiredOnCreate(json, create, "name", "slug");
        if (json.has("status") && create) throw invalid();
        Set<String> supplied = names(json);
        String name = json.has("name") ? name(json.get("name")) : null;
        String slug = json.has("slug") ? slug(json.get("slug")) : null;
        Integer order = json.has("displayOrder") ? Integer.valueOf(order(json.get("displayOrder"))) : create ? Integer.valueOf(0) : null;
        TagStatus status = json.has("status") ? tagStatus(json.get("status")) : null;
        return new TagChanges(Set.copyOf(supplied), name, slug, order, status);
    }

    private static void object(JsonNode node) { if (node == null || !node.isObject()) throw invalid(); }
    private static void fields(JsonNode node, Set<String> allowed) {
        node.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) throw invalid(); });
    }
    private static Set<String> names(JsonNode node) {
        Set<String> result = new HashSet<>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }
    private static void requiredOnCreate(JsonNode node, boolean create, String... required) {
        if (create) for (String field : required) if (!node.has(field)) throw invalid();
    }
    private static String name(JsonNode node) {
        if (node == null || !node.isTextual()) throw invalid();
        String value = node.textValue().strip();
        validateUnicode(value);
        int length = value.codePointCount(0, value.length());
        if (length < 1 || length > 50) throw invalid();
        return value;
    }
    private static String slug(JsonNode node) {
        if (node == null || !node.isTextual()) throw invalid();
        String value = node.textValue().toLowerCase(Locale.ROOT);
        validateUnicode(value);
        if (value.length() < 1 || value.length() > 100 || !value.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) throw invalid();
        return value;
    }
    private static void validateUnicode(String value) {
        for (int offset = 0; offset < value.length();) {
            char current = value.charAt(offset);
            if (current == '\0' || Character.isLowSurrogate(current)) throw invalid();
            if (Character.isHighSurrogate(current)) {
                if (offset + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(offset + 1))) throw invalid();
                offset += 2;
            } else {
                offset++;
            }
        }
    }
    private static Long id(JsonNode node) {
        if (!node.isTextual() || !node.textValue().matches("[1-9][0-9]{0,18}")) throw invalid();
        try { return Long.parseLong(node.textValue()); } catch (NumberFormatException e) { throw invalid(); }
    }
    private static int order(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 0) throw invalid();
        return node.intValue();
    }
    private static CategoryStatus categoryStatus(JsonNode node) {
        try { return CategoryStatus.valueOf(textStatus(node)); } catch (IllegalArgumentException e) { throw invalid(); }
    }
    private static TagStatus tagStatus(JsonNode node) {
        try { return TagStatus.valueOf(textStatus(node)); } catch (IllegalArgumentException e) { throw invalid(); }
    }
    private static String textStatus(JsonNode node) { if (node == null || !node.isTextual()) throw invalid(); return node.textValue(); }
    private static ApplicationException invalid() { return new ApplicationException(GlobalErrorCode.INVALID_REQUEST); }
}
