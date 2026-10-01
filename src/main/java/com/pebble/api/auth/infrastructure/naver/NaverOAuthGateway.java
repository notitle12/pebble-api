package com.pebble.api.auth.infrastructure.naver;

public interface NaverOAuthGateway {

    NaverProfile authenticate(String authorizationCode, String state);

    String authorizationUrl(String state);

    record NaverProfile(String subject, String nickname, String profileImageUrl) {
    }
}
