package com.pebble.api.auth.application.oauth;

/** 외부 공급자 인증 결과를 Pebble 회원 연결에 필요한 정보로 정규화한다. */
public interface OAuthProviderClient {
    OAuthProfile authenticate(String authorizationCode, String state);
    String authorizationUrl(String state);

    record OAuthProfile(String subject, String nickname, String profileImageUrl) {
        @Override
        public String toString() {
            return "OAuthProfile[personalData=redacted]";
        }
    }
}
