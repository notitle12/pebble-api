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
import java.util.Locale;
import java.util.Set;

/** TABLE 블록의 버전과 구조를 검사한다. 임의 SQL이나 HTML로 실행하지 않는다. */
final class TableSpecInput {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(50000).build())
            .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> ROOT = Set.of("schemaVersion", "tableName", "description", "columns");
    private static final Set<String> COLUMN = Set.of("name", "dataType", "nullable", "primaryKey", "foreignKey", "description");

    private TableSpecInput() {}

    static void validate(String content, String field) {
        JsonNode table;
        try { table = JSON.readTree(content); }
        catch (JsonProcessingException exception) {
            fail(field, "올바른 테이블 명세서 JSON을 입력해 주세요.");
            return;
        }
        object(table, ROOT, field);
        JsonNode version = table.get("schemaVersion");
        if (version == null || !version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 1) {
            fail(field + ".schemaVersion", "명세서 버전 1만 지원합니다.");
        }
        text(table.get("tableName"), field + ".tableName", 100, true);
        optionalText(table, "description", field, 500);
        JsonNode columns = table.get("columns");
        if (columns == null || !columns.isArray() || columns.isEmpty() || columns.size() > 50) {
            fail(field + ".columns", "컬럼을 1~50개 입력해 주세요.");
        }
        Set<String> names = new HashSet<>();
        int index = 0;
        for (JsonNode column : columns) {
            String prefix = field + ".columns[" + index++ + "]";
            object(column, COLUMN, prefix);
            String name = text(column.get("name"), prefix + ".name", 100, true);
            if (!names.add(name.strip().toLowerCase(Locale.ROOT))) fail(prefix + ".name", "중복된 컬럼명은 사용할 수 없습니다.");
            text(column.get("dataType"), prefix + ".dataType", 100, true);
            boolean nullable = bool(column.get("nullable"), prefix + ".nullable");
            boolean primaryKey = bool(column.get("primaryKey"), prefix + ".primaryKey");
            if (primaryKey && nullable) fail(prefix + ".nullable", "기본 키는 NULL을 허용할 수 없습니다.");
            optionalText(column, "foreignKey", prefix, 200);
            optionalText(column, "description", prefix, 500);
        }
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
