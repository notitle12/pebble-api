package com.pebble.api.post.presentation.dto;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 제한형 아키텍처의 요소·참조를 검사한다. 범위가 제한된 좌표와 기술 아이콘을 허용하고 실행 코드는 받지 않는다. */
final class ArchitectureSpecInput {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(50000).build())
            .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> GROUP_TYPES = Set.of("ORACLE_CLOUD", "AWS", "CLOUDFLARE", "DOCKER", "CUSTOM");
    private static final Set<String> NODE_TYPES = Set.of("CLIENT", "APP", "DATABASE", "CACHE", "STORAGE", "PROXY", "CUSTOM");
    private static final Set<String> ICONS = Set.of("AWS", "ORACLE_CLOUD", "CLOUDFLARE", "DOCKER", "SPRING",
            "POSTGRESQL", "REDIS", "R2", "WORKERS", "NGINX", "NODEJS", "REACT", "SERVER", "DATABASE",
            "CACHE", "STORAGE", "CLIENT", "CLOUD", "CONTAINER");
    private ArchitectureSpecInput() {}

    static void validate(String content, String field) {
        JsonNode spec;
        try { spec = JSON.readTree(content); }
        catch (JsonProcessingException exception) {
            fail(field, "올바른 아키텍처 JSON을 입력해 주세요.");
            return;
        }
        object(spec, Set.of("schemaVersion", "groups", "nodes", "edges"), field);
        JsonNode version = spec.get("schemaVersion");
        if (version == null || !version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 1)
            fail(field + ".schemaVersion", "아키텍처 버전 1만 지원합니다.");
        JsonNode groups = array(spec, "groups", field, 0, 10);
        JsonNode nodes = array(spec, "nodes", field, 1, 30);
        JsonNode edges = array(spec, "edges", field, 0, 60);
        Set<String> ids = new HashSet<>();
        Map<String, String> groupTypes = new HashMap<>();
        Set<String> nodeIds = new HashSet<>();
        int index = 0;
        for (JsonNode group : groups) {
            String prefix = field + ".groups[" + index++ + "]";
            object(group, Set.of("id", "type", "label", "parentId", "bounds"), prefix);
            String id = uniqueId(group.get("id"), prefix + ".id", ids);
            String type = text(group.get("type"), prefix + ".type", 40, true);
            if (!GROUP_TYPES.contains(type)) fail(prefix + ".type", "지원하지 않는 그룹입니다.");
            text(group.get("label"), prefix + ".label", 100, true);
            JsonNode bounds = group.get("bounds");
            if (bounds != null && !bounds.isNull()) {
                object(bounds, Set.of("x", "y", "width", "height"), prefix + ".bounds");
                coordinate(bounds.get("x"), prefix + ".bounds.x");
                coordinate(bounds.get("y"), prefix + ".bounds.y");
                dimension(bounds.get("width"), prefix + ".bounds.width", 200);
                dimension(bounds.get("height"), prefix + ".bounds.height", 120);
                if (bounds.get("x").intValue() + bounds.get("width").intValue() > 4200
                        || bounds.get("y").intValue() + bounds.get("height").intValue() > 4200)
                    fail(prefix + ".bounds", "경계 영역은 4200 좌표를 넘을 수 없습니다.");
            }
            groupTypes.put(id, type);
        }
        index = 0;
        for (JsonNode group : groups) {
            String prefix = field + ".groups[" + index++ + "]";
            String parent = optionalId(group, "parentId", prefix);
            if (parent != null && (!group.get("type").textValue().equals("DOCKER")
                    || !groupTypes.containsKey(parent) || groupTypes.get(parent).equals("DOCKER")))
                fail(prefix + ".parentId", "Docker만 클라우드 그룹 안에 배치할 수 있습니다.");
        }
        index = 0;
        for (JsonNode node : nodes) {
            String prefix = field + ".nodes[" + index++ + "]";
            object(node, Set.of("id", "type", "label", "groupId", "icon", "position"), prefix);
            nodeIds.add(uniqueId(node.get("id"), prefix + ".id", ids));
            String type = text(node.get("type"), prefix + ".type", 40, true);
            if (!NODE_TYPES.contains(type)) fail(prefix + ".type", "지원하지 않는 요소입니다.");
            text(node.get("label"), prefix + ".label", 100, true);
            JsonNode icon = node.get("icon");
            if (icon != null && !icon.isNull() && (!icon.isTextual() || !ICONS.contains(icon.textValue())))
                fail(prefix + ".icon", "지원하는 기술 또는 기본 아이콘을 선택해 주세요.");
            JsonNode position = node.get("position");
            if (position != null && !position.isNull()) {
                object(position, Set.of("x", "y"), prefix + ".position");
                coordinate(position.get("x"), prefix + ".position.x");
                coordinate(position.get("y"), prefix + ".position.y");
            }
            String group = optionalId(node, "groupId", prefix);
            if (group != null && !groupTypes.containsKey(group)) fail(prefix + ".groupId", "존재하는 그룹을 선택해 주세요.");
        }
        Set<String> pairs = new HashSet<>();
        index = 0;
        for (JsonNode edge : edges) {
            String prefix = field + ".edges[" + index++ + "]";
            object(edge, Set.of("id", "source", "target", "label", "sourceSide", "targetSide", "waypoint"), prefix);
            uniqueId(edge.get("id"), prefix + ".id", ids);
            String source = identifier(edge.get("source"), prefix + ".source");
            String target = identifier(edge.get("target"), prefix + ".target");
            if (!(nodeIds.contains(source) || groupTypes.containsKey(source)) || !(nodeIds.contains(target) || groupTypes.containsKey(target))) fail(prefix, "존재하는 요소 또는 그룹끼리 연결해 주세요.");
            if (source.equals(target) || !pairs.add(source + ":" + target)) fail(prefix, "자기 연결이나 같은 방향의 중복 연결은 허용하지 않습니다.");
            optionalText(edge, "label", prefix, 200);
            for (String side : Set.of("sourceSide", "targetSide")) {
                JsonNode value = edge.get(side);
                if (value != null && !value.isNull() && (!value.isTextual()
                        || !Set.of("TOP", "RIGHT", "BOTTOM", "LEFT").contains(value.textValue())))
                    fail(prefix + "." + side, "위·오른쪽·아래·왼쪽 연결 위치를 선택해 주세요.");
            }
            JsonNode waypoint = edge.get("waypoint");
            if (waypoint != null && !waypoint.isNull()) {
                object(waypoint, Set.of("x", "y"), prefix + ".waypoint");
                for (String axis : Set.of("x", "y")) {
                    JsonNode value = waypoint.get(axis);
                    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                            || value.intValue() < 0 || value.intValue() > 4200)
                        fail(prefix + ".waypoint." + axis, "0~4200 범위의 정수 좌표를 입력해 주세요.");
                }
            }
        }
    }

    private static void dimension(JsonNode value, String field, int min) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < min || value.intValue() > 4200)
            fail(field, min + "~4200 범위의 정수 크기를 입력해 주세요.");
    }

    private static void coordinate(JsonNode value, String field) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < 0 || value.intValue() > 4000)
            fail(field, "0~4000 범위의 정수 좌표를 입력해 주세요.");
    }

    private static JsonNode array(JsonNode spec, String key, String field, int min, int max) {
        JsonNode value = spec.get(key);
        if (value == null || !value.isArray() || value.size() < min || value.size() > max)
            fail(field + "." + key, min + "~" + max + "개 배열을 입력해 주세요.");
        return value;
    }
    private static String identifier(JsonNode value, String field) {
        String id = text(value, field, 40, true);
        if (!id.matches("[a-z][a-z0-9-]{0,39}")) fail(field, "영문 소문자로 시작하는 소문자·숫자·하이픈 ID를 입력해 주세요.");
        return id;
    }
    private static String uniqueId(JsonNode value, String field, Set<String> ids) {
        String id = identifier(value, field);
        if (!ids.add(id)) fail(field, "모든 요소의 ID는 서로 달라야 합니다.");
        return id;
    }
    private static String optionalId(JsonNode object, String key, String field) {
        JsonNode value = object.get(key);
        return value == null || value.isNull() ? null : identifier(value, field + "." + key);
    }
    private static void object(JsonNode value, Set<String> fields, String field) {
        if (value == null || !value.isObject()) fail(field, "객체를 입력해 주세요.");
        value.fieldNames().forEachRemaining(key -> {
            if (!fields.contains(key)) fail(field + "." + key, "허용하지 않는 필드입니다.");
        });
    }

    private static void optionalText(JsonNode object, String key, String field, int max) {
        JsonNode value = object.get(key);
        if (value != null && !value.isNull()) text(value, field + "." + key, max, false);
    }

    private static String text(JsonNode value, String field, int max, boolean required) {
        if (value == null || !value.isTextual()) fail(field, "문자열을 입력해 주세요.");
        String text = value.textValue();
        if (required && text.isBlank()) fail(field, "비어 있지 않은 값을 입력해 주세요.");
        if (text.codePointCount(0, text.length()) > max) fail(field, "최대 " + max + "자까지 입력할 수 있습니다.");
        for (int offset = 0; offset < text.length();) {
            int point = text.codePointAt(offset);
            if (point == 0 || (point >= 0xD800 && point <= 0xDFFF)) fail(field, "올바른 유니코드 문자열을 입력해 주세요.");
            offset += Character.charCount(point);
        }
        return text;
    }

    private static void fail(String field, String reason) {
        throw new ApplicationException(GlobalErrorCode.VALIDATION_ERROR, field, reason);
    }
}
