package com.pebble.api.comment.presentation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** 비밀번호가 예외·자동 문자열 출력에 포함되지 않도록 원문 DTO를 만들지 않는다. */
public final class GuestCommentRequest {
    private GuestCommentRequest() { }
    public static void validate(JsonNode json, Set<String> fields) {
        if(json==null || !json.isObject() || json.size()!=fields.size()) throw invalid();
        json.fieldNames().forEachRemaining(key->{if(!fields.contains(key))throw invalid();});
    }
    public static String nickname(JsonNode json) {
        String value=text(json,"nickname").strip();
        if(value.isEmpty() || value.codePointCount(0,value.length())>30 || value.codePoints().anyMatch(Character::isISOControl))throw invalid();
        return value;
    }
    public static String password(JsonNode json) {
        String value=text(json,"password");
        int count=value.codePointCount(0,value.length());
        if(count<8 || count>64 || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length>256 || value.codePoints().anyMatch(Character::isISOControl))throw invalid();
        return value;
    }
    public static String body(JsonNode json) {
        return CommentWriteRequest.parse(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("body",text(json,"body")).put("visibility","PUBLIC"),true).body();
    }
    public static void confirmations(JsonNode json) {
        for(String key: new String[]{"privacyAccepted","ageConfirmed"})
            if(!json.path(key).isBoolean() || !json.path(key).booleanValue())throw invalid();
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
