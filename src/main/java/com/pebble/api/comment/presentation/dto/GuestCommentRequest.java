package com.pebble.api.comment.presentation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** 비밀번호가 예외·자동 문자열 출력에 포함되지 않도록 원문 DTO를 만들지 않는다. */
public final class GuestCommentRequest {
    private static final String DEFAULT_NICKNAME = "조약돌";
    private static final Set<String> CREATE_REQUIRED_FIELDS = Set.of("password", "body");
    private static final Set<String> CREATE_ALLOWED_FIELDS = Set.of(
            "nickname", "password", "body", "privacyAccepted", "ageConfirmed");

    private GuestCommentRequest() { }
    public static void validate(JsonNode json, Set<String> fields) {
        if(json==null || !json.isObject() || json.size()!=fields.size()) throw invalid();
        json.fieldNames().forEachRemaining(key->{if(!fields.contains(key))throw invalid();});
    }
    public static void validateCreate(JsonNode json) {
        if (json == null || !json.isObject()) throw invalid();
        for (String key : CREATE_REQUIRED_FIELDS) if (!json.has(key)) throw invalid();
        json.fieldNames().forEachRemaining(key -> {
            if (!CREATE_ALLOWED_FIELDS.contains(key)) throw invalid();
            if ((key.equals("privacyAccepted") || key.equals("ageConfirmed")) && !json.get(key).isBoolean()) throw invalid();
        });
    }
    public static String nickname(JsonNode json) {
        JsonNode node = json == null ? null : json.get("nickname");
        if (node == null) return DEFAULT_NICKNAME;
        if (!node.isTextual()) throw invalid();
        String value=text(json,"nickname").strip();
        if(value.isEmpty()) return DEFAULT_NICKNAME;
        if(value.codePointCount(0,value.length())>30 || value.codePoints().anyMatch(Character::isISOControl))throw invalid();
        return value;
    }
    public static String password(JsonNode json) {
        return password(json, 4, 64);
    }
    public static String newPassword(JsonNode json) {
        return password(json, 4, 10);
    }
    private static String password(JsonNode json, int minimum, int maximum) {
        String value=text(json,"password");
        int count=value.codePointCount(0,value.length());
        if(count<minimum || count>maximum || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length>256 || value.codePoints().anyMatch(Character::isISOControl))throw invalid();
        return value;
    }
    public static String body(JsonNode json) {
        return CommentWriteRequest.parse(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("body",text(json,"body")).put("visibility","PUBLIC"),true).body();
    }
    private static String text(JsonNode json,String key) {
        var node=json.get(key);
        if(node==null || !node.isTextual())throw invalid();
        String value=node.textValue();
        if(value.codePoints().anyMatch(point->point==0 || point>=0xD800 && point<=0xDFFF))throw invalid();
        return value;
    }
    private static ApplicationException invalid(){return new ApplicationException(GlobalErrorCode.INVALID_REQUEST);}
}
