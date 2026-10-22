package com.pebble.api.auth.application.oauth;

/** 외부 공급자 인증 결과를 Pebble 회원 연결에 필요한 정보로 정규화한다. */
public interface OAuthProviderClient {
    OAuthProfile authenticate(String authorizationCode, String state);
    String authorizationUrl(String state);

    default String withdrawalAuthorizationUrl(String state) {
        throw new UnsupportedOperationException("Withdrawal is not supported");
    }

    default void authenticateAndRevoke(String authorizationCode, String state, String expectedSubject) {
        throw new UnsupportedOperationException("Withdrawal is not supported");
    }

    /** 각 공급자 서버에서 확인한 서비스 동의. 브라우저 입력으로 생성하지 않는다. */
    record VerifiedServiceAgreement(String termsVersion, String privacyVersion) {}

    record OAuthProfile(String subject, String nickname, String profileImageUrl, VerifiedServiceAgreement serviceAgreement) {
        public OAuthProfile(String subject, String nickname, String profileImageUrl) {
            this(subject, nickname, profileImageUrl, null);
        }
        @Override
        public String toString() {
            return "OAuthProfile[personalData=redacted]";
        }
    }
}
