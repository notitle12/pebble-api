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
import java.util.Locale;
import java.util.Set;

/** Validates the shared ERD/specification document, including stable references. */
public final class DatabaseSchemaInput {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12).maxStringLength(50000).build())
            .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private DatabaseSchemaInput() {}
    public static void validate(String content, String field) {
        JsonNode schema;
        try { schema = JSON.readTree(content); }
        catch (JsonProcessingException exception) { fail(field, "올바른 DB 설계 JSON을 입력해 주세요."); return; }
        object(schema, Set.of("schemaVersion", "display", "tables"), field);
        JsonNode version = schema.get("schemaVersion");
        if (version == null || !version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 1) fail(field, "스키마 버전 1만 지원합니다.");
        String display = text(schema.get("display"), field + ".display", 4, true);
        if (!Set.of("BOTH", "ERD", "SPEC").contains(display)) fail(field, "표시 방식을 확인해 주세요.");
        JsonNode tables = schema.get("tables");
        if (tables == null || !tables.isArray() || tables.isEmpty() || tables.size() > 20) fail(field, "테이블을 1~20개 입력해 주세요.");
        Map<String, Map<String, JsonNode>> ids = new HashMap<>();
        Set<String> tableNames = new HashSet<>();
        for (JsonNode table : tables) {
            object(table, Set.of("id", "name", "description", "position", "columns"), field);
            String tableId = identifier(table.get("id"), field);
            String name = text(table.get("name"), field, 100, true);
            text(table.get("description"), field, 500, false);
            if (!tableNames.add(name.strip().toLowerCase(Locale.ROOT)) || ids.containsKey(tableId)) fail(field, "테이블명과 ID는 중복될 수 없습니다.");
            JsonNode position = table.get("position");
            object(position, Set.of("x", "y"), field);
            for (String axis : Set.of("x", "y")) {
                JsonNode point = position.get(axis);
                if (point == null || !point.isIntegralNumber() || !point.canConvertToInt() || point.intValue() < 0 || point.intValue() > 6000) fail(field, "좌표는 0~6000의 정수여야 합니다.");
            }
            JsonNode columns = table.get("columns");
            if (columns == null || !columns.isArray() || columns.isEmpty() || columns.size() > 50) fail(field, "컬럼을 1~50개 입력해 주세요.");
            Map<String, JsonNode> columnIds = new HashMap<>();
            Set<String> names = new HashSet<>();
            for (JsonNode column : columns) {
                object(column, Set.of("id", "name", "dataType", "nullable", "primaryKey", "unique", "description", "reference"), field);
                String columnId = identifier(column.get("id"), field);
                String columnName = text(column.get("name"), field, 100, true);
                text(column.get("dataType"), field, 100, true);
                text(column.get("description"), field, 500, false);
                boolean nullable = bool(column.get("nullable"), field);
                boolean pk = bool(column.get("primaryKey"), field);
                bool(column.get("unique"), field);
                if (nullable && pk) fail(field, "PK는 NULL을 허용할 수 없습니다.");
                if (!names.add(columnName.strip().toLowerCase(Locale.ROOT)) || columnIds.put(columnId, column) != null) fail(field, "컬럼명과 ID는 중복될 수 없습니다.");
                JsonNode ref = column.get("reference");
                if (ref == null) fail(field, "참조 또는 null을 입력해 주세요.");
                if (!ref.isNull()) {
                    object(ref, Set.of("tableId", "columnId", "notation", "sourceRequired"), field);
                    identifier(ref.get("tableId"), field); identifier(ref.get("columnId"), field);
                    if (ref.has("notation") && !Set.of("IE", "BARKER", "CHEN").contains(text(ref.get("notation"), field, 6, true))) fail(field, "관계선 표기법을 확인해 주세요.");
                    if (ref.has("sourceRequired")) bool(ref.get("sourceRequired"), field);
                }
            }
            ids.put(tableId, columnIds);
        }
        for (JsonNode table : tables) for (JsonNode column : table.get("columns")) {
            JsonNode ref = column.get("reference");
            if (ref.isNull()) continue;
            String tableId = ref.get("tableId").textValue(), columnId = ref.get("columnId").textValue();
            JsonNode target = ids.getOrDefault(tableId, Map.of()).get(columnId);
            long primaryKeys = ids.getOrDefault(tableId, Map.of()).values().stream().filter(c -> c.get("primaryKey").booleanValue()).count();
            if (target == null || (!target.get("unique").booleanValue() && !(target.get("primaryKey").booleanValue() && primaryKeys == 1))) fail(field, "존재하는 PK 또는 UNIQUE 컬럼을 참조해 주세요.");
            if (tableId.equals(table.get("id").textValue()) && columnId.equals(column.get("id").textValue())) fail(field, "자기 자신인 컬럼을 참조할 수 없습니다.");
        }
    }
    private static String identifier(JsonNode value, String field) {
        String id = text(value, field, 40, true);
        if (!id.matches("[a-z][a-z0-9-]{0,39}")) fail(field, "올바른 내부 식별자를 입력해 주세요.");
        return id;
    }
    private static void object(JsonNode value, Set<String> fields, String field) {
        if (value == null || !value.isObject()) fail(field, "객체를 입력해 주세요.");
        value.fieldNames().forEachRemaining(key -> {
            if (!fields.contains(key)) fail(field + "." + key, "허용하지 않는 필드입니다.");
        });
    }

    private static boolean bool(JsonNode value, String field) {
        if (value == null || !value.isBoolean()) fail(field, "boolean 값을 입력해 주세요.");
        return value.booleanValue();
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
