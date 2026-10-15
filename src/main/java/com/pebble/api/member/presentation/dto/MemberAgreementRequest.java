package com.pebble.api.member.presentation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import java.util.Set;

public record MemberAgreementRequest(String termsVersion, String privacyVersion) {
    public static MemberAgreementRequest parse(JsonNode json) {
        Set<String> allowed = Set.of("termsVersion", "privacyVersion", "termsAccepted", "privacyAccepted", "ageConfirmed");
        if (json == null || !json.isObject() || json.size() != allowed.size()) throw invalid();
        json.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) throw invalid(); });
        for (String field : Set.of("termsAccepted", "privacyAccepted", "ageConfirmed")) {
            if (!json.path(field).isBoolean() || !json.path(field).booleanValue()) throw invalid();
        }
        for (String field : Set.of("termsVersion", "privacyVersion")) {
            if (!json.path(field).isTextual() || json.path(field).textValue().isBlank() || json.path(field).textValue().length() > 32) throw invalid();
        }
        return new MemberAgreementRequest(json.path("termsVersion").textValue(), json.path("privacyVersion").textValue());
    }
    private static ApplicationException invalid() { return new ApplicationException(GlobalErrorCode.INVALID_REQUEST); }
}
