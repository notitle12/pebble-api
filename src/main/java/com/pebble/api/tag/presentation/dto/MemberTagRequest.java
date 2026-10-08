package com.pebble.api.tag.presentation.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import java.io.IOException;
import java.text.Normalizer;
import java.util.Locale;

/** 회원 자유 태그의 JSON 및 저장 이름을 검증한다. */
public final class MemberTagRequest {
    private MemberTagRequest() {}

    public static String parse(ObjectMapper mapper, String raw) {
        JsonNode json;
        try {
            json = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw == null ? "" : raw);
        } catch (IOException exception) {
            throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        }
        if (json == null || !json.isObject() || json.size() != 1 || !json.has("name") || !json.get("name").isTextual()) {
            throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        }
        String rawName = json.get("name").textValue();
        rawName.codePoints().forEach(point -> {
            if (point == 0 || point >= 0xD800 && point <= 0xDFFF) fail();
        });
        String name = Normalizer.normalize(rawName, Normalizer.Form.NFKC).strip();
        if (name.startsWith("#")) name = name.substring(1);
        name = name.toLowerCase(Locale.ROOT);
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 50) fail();
        boolean hasLetterOrNumber = false;
        for (int point : name.codePoints().toArray()) {
            int type = Character.getType(point);
            boolean number = type == Character.DECIMAL_DIGIT_NUMBER || type == Character.LETTER_NUMBER || type == Character.OTHER_NUMBER;
            boolean mark = type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK;
            if (!(Character.isLetter(point) || number || mark || point == '_' || point == '-' || point == '.' || point == '+')) fail();
            hasLetterOrNumber |= Character.isLetter(point) || number;
        }
        if (!hasLetterOrNumber) fail();
        return name;
    }

    private static void fail() {
        throw new ApplicationException(GlobalErrorCode.VALIDATION_ERROR, "name", "태그는 1~50자의 문자·숫자·결합 문자와 _ - . +만 사용할 수 있으며 문자 또는 숫자를 포함해야 합니다.");
    }
}
