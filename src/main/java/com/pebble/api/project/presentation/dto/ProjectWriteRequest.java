package com.pebble.api.project.presentation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.project.application.ProjectChanges;
import com.pebble.api.project.application.ProjectChanges.FeatureInput;
import com.pebble.api.project.application.ProjectChanges.LinkInput;
import com.pebble.api.project.domain.ProjectLifecycleStatus;
import com.pebble.api.project.domain.ProjectLinkType;
import com.pebble.api.project.domain.ProjectVisibility;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Project 요청 JSON의 필드 존재 여부와 형식을 검증한다. */
public final class ProjectWriteRequest {
    private static final Set<String> FIELDS = Set.of("name", "summary", "description", "architectureDescription",
            "executionInstructions", "lifecycleStatus", "startedOn", "completedOn", "tagIds", "features",
            "links", "visibilityStatus");

    private ProjectWriteRequest() { }

    public static ProjectChanges parse(JsonNode json, boolean create) {
        object(json, FIELDS);
        if (create) {
            for (String required : List.of("name", "lifecycleStatus", "visibilityStatus")) {
                if (!json.has(required)) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
            }
        }
        Set<String> supplied = new HashSet<>();
        json.fieldNames().forEachRemaining(supplied::add);
        String name = json.has("name") ? text(json.get("name"), "name", 120, false, true) : null;
        String summary = json.has("summary") ? text(json.get("summary"), "summary", 500, true, false) : null;
        String description = json.has("description") ? text(json.get("description"), "description", 20_000, true, false) : null;
        String architecture = json.has("architectureDescription")
                ? text(json.get("architectureDescription"), "architectureDescription", 20_000, true, false) : null;
        String execution = json.has("executionInstructions")
                ? text(json.get("executionInstructions"), "executionInstructions", 10_000, true, false) : null;
        ProjectLifecycleStatus lifecycle = json.has("lifecycleStatus")
                ? enumValue(json.get("lifecycleStatus"), ProjectLifecycleStatus.class, "lifecycleStatus") : null;
        LocalDate startedOn = json.has("startedOn") ? date(json.get("startedOn"), "startedOn") : null;
        LocalDate completedOn = json.has("completedOn") ? date(json.get("completedOn"), "completedOn") : null;
        List<Long> tagIds = json.has("tagIds") ? ids(json.get("tagIds"), "tagIds") : (create ? List.of() : null);
        List<FeatureInput> features = json.has("features") ? features(json.get("features")) : (create ? List.of() : null);
        List<LinkInput> links = json.has("links") ? links(json.get("links")) : (create ? List.of() : null);
        ProjectVisibility visibility = json.has("visibilityStatus")
                ? enumValue(json.get("visibilityStatus"), ProjectVisibility.class, "visibilityStatus") : null;
        if (visibility == ProjectVisibility.DELETED) fail("visibilityStatus", "PUBLIC 또는 HIDDEN을 지정해 주세요.");
        return new ProjectChanges(Set.copyOf(supplied), name, summary, description, architecture, execution,
                lifecycle, startedOn, completedOn, tagIds, features, links, visibility);
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

    private static List<Long> ids(JsonNode node, String field) {
        if (node == null || !node.isArray()) fail(field, "ID 배열을 입력해 주세요.");
        List<Long> result = new ArrayList<>();
        Set<Long> distinct = new HashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) fail(field, "ID는 문자열로 입력해 주세요.");
            long id = id(item.textValue(), field);
            if (!distinct.add(id)) fail(field, "중복된 Tag를 지정할 수 없습니다.");
            result.add(id);
        }
        return List.copyOf(result);
    }

    private static List<FeatureInput> features(JsonNode node) {
        if (node == null || !node.isArray()) fail("features", "배열을 입력해 주세요.");
        List<FeatureInput> result = new ArrayList<>();
        int index = 0;
        for (JsonNode item : node) {
            object(item, Set.of("title", "description"));
            String prefix = "features[" + index++ + "].";
            result.add(new FeatureInput(text(item.get("title"), prefix + "title", 100, false, true),
                    text(item.get("description"), prefix + "description", 2_000, false, false)));
        }
        return List.copyOf(result);
    }

    private static List<LinkInput> links(JsonNode node) {
        if (node == null || !node.isArray()) fail("links", "배열을 입력해 주세요.");
        List<LinkInput> result = new ArrayList<>();
        int index = 0;
        for (JsonNode item : node) {
            object(item, Set.of("linkType", "label", "url", "displayOrder"));
            String prefix = "links[" + index++ + "].";
            ProjectLinkType type = enumValue(item.get("linkType"), ProjectLinkType.class, prefix + "linkType");
            String label = item.has("label") ? text(item.get("label"), prefix + "label", 100, true, false) : null;
            String url = text(item.get("url"), prefix + "url", 2_048, false, true);
            validateUrl(url, prefix + "url");
            JsonNode order = item.get("displayOrder");
            if (order == null || !order.isIntegralNumber() || !order.canConvertToInt() || order.intValue() < 0) {
                fail(prefix + "displayOrder", "0 이상의 정수를 입력해 주세요.");
            }
            result.add(new LinkInput(type, label, url, order.intValue()));
        }
        return List.copyOf(result);
    }

    private static LocalDate date(JsonNode node, String field) {
        if (node != null && node.isNull()) return null;
        if (node == null || !node.isTextual()) fail(field, "YYYY-MM-DD 날짜 또는 null을 입력해 주세요.");
        if (!node.textValue().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) fail(field, "YYYY-MM-DD 날짜를 입력해 주세요.");
        try {
            return LocalDate.parse(node.textValue());
        } catch (DateTimeParseException exception) {
            fail(field, "YYYY-MM-DD 날짜를 입력해 주세요.");
            throw new AssertionError();
        }
    }

    private static void object(JsonNode json, Set<String> fields) {
        if (json == null || !json.isObject()) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        json.fieldNames().forEachRemaining(field -> {
            if (!fields.contains(field)) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        });
    }

    private static void validateUrl(String value, String field) {
        try {
            URI uri = URI.create(value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null) {
                fail(field, "사용자 정보가 없는 절대 HTTP(S) URL을 입력해 주세요.");
            }
        } catch (IllegalArgumentException exception) {
            fail(field, "올바른 HTTP(S) URL을 입력해 주세요.");
        }
    }

    private static String text(JsonNode node, String field, int max, boolean nullable, boolean nonBlank) {
        if (nullable && node != null && node.isNull()) return null;
        if (node == null || !node.isTextual()) fail(field, "문자열을 입력해 주세요.");
        String value = node.textValue();
        for (int offset = 0; offset < value.length();) {
            int point = value.codePointAt(offset);
            if (point == 0 || (point >= 0xD800 && point <= 0xDFFF)) fail(field, "올바른 유니코드 문자열을 입력해 주세요.");
            offset += Character.charCount(point);
        }
        if (value.codePointCount(0, value.length()) > max) fail(field, "최대 " + max + "자까지 입력할 수 있습니다.");
        if (nonBlank && value.isBlank()) fail(field, "비어 있지 않은 값을 입력해 주세요.");
        return value;
    }

    private static <E extends Enum<E>> E enumValue(JsonNode node, Class<E> type, String field) {
        if (node == null || !node.isTextual()) fail(field, "허용된 값을 입력해 주세요.");
        try {
            return Enum.valueOf(type, node.textValue());
        } catch (IllegalArgumentException exception) {
            fail(field, "허용된 값을 입력해 주세요.");
            throw new AssertionError();
        }
    }

    private static void fail(String field, String reason) {
        throw new ApplicationException(GlobalErrorCode.VALIDATION_ERROR, field, reason);
    }
}
