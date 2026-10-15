package com.pebble.api.auth.infrastructure.naver;

import com.pebble.api.auth.application.oauth.OAuthProviderClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pebble.api.auth.domain.AuthError;
import com.pebble.api.auth.domain.AuthException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

@Component("naverOAuthClient")
public class NaverOAuthClient implements OAuthProviderClient {

    private final RestClient restClient;
    private final NaverOAuthProperties properties;
    private final java.time.Clock clock;

    public NaverOAuthClient(@Qualifier("naverRestClient") RestClient restClient, NaverOAuthProperties properties, java.time.Clock clock) {
        this.restClient = restClient;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String authorizationUrl(String state) {
        requireClientConfiguration();
        return UriComponentsBuilder.fromUriString(properties.authorizationUri())
                .queryParam("response_type", "code")
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("state", state)
                .build()
                .encode()
                .toUriString();
    }

    @Override
    public String withdrawalAuthorizationUrl(String state) {
        return authorizationUrl(state) + "&auth_type=reauthenticate";
    }

    @Override
    public void authenticateAndRevoke(String authorizationCode, String state, String expectedSubject) {
        authenticate(authorizationCode, state, expectedSubject);
    }

    @Override
    public OAuthProfile authenticate(String authorizationCode, String state) {
        return authenticate(authorizationCode, state, null);
    }

    private OAuthProfile authenticate(String authorizationCode, String state, String expectedSubject) {
        requireClientConfiguration();
        try {
            NaverTokenResponse token = restClient.post()
                    .uri(properties.tokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(tokenRequest(authorizationCode, state))
                    .retrieve()
                    .body(NaverTokenResponse.class);
            if (token != null && ("server_error".equals(token.error()) || "temporarily_unavailable".equals(token.error()))) {
                throw new AuthException(AuthError.OAUTH_PROVIDER_UNAVAILABLE);
            }
            if (token == null || isBlank(token.accessToken()) || token.error() != null) {
                throw new AuthException(AuthError.INVALID_CREDENTIALS);
            }
            if (!"bearer".equalsIgnoreCase(token.tokenType())) {
                throw new AuthException(AuthError.OAUTH_PROVIDER_UNAVAILABLE);
            }

            NaverProfileResponse profileResponse = restClient.get()
                    .uri(properties.profileUri())
                    .headers(headers -> headers.setBearerAuth(token.accessToken()))
                    .retrieve()
                    .body(NaverProfileResponse.class);
            if (profileResponse == null || !"00".equals(profileResponse.resultCode())
                    || profileResponse.response() == null || isBlank(profileResponse.response().id())) {
                throw new AuthException(AuthError.OAUTH_PROVIDER_UNAVAILABLE);
            }
            NaverProfileFields profile = profileResponse.response();
            if (characterCount(profile.id()) > 255
                    || (profile.nickname() != null && characterCount(profile.nickname()) > 30)) {
                throw new AuthException(AuthError.OAUTH_PROVIDER_UNAVAILABLE);
            }
            if (expectedSubject != null) {
                if (!expectedSubject.equals(profile.id())) throw new AuthException(AuthError.INVALID_CREDENTIALS);
                MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                form.add("client_id", properties.clientId());
                form.add("client_secret", properties.clientSecret());
                form.add("token", token.accessToken());
                form.add("token_type_hint", "access_token");
                // 공급자 토큰은 현재 요청에서만 사용하고 DB·Redis·로그에 남기지 않는다.
                try {
                    restClient.post().uri("https://nid.naver.com/oauth2.0/revoke")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                            .retrieve().toBodilessEntity();
                } catch (RestClientException exception) {
                    throw new AuthException(AuthError.OAUTH_PROVIDER_UNAVAILABLE);
                }
            }
            // 탈퇴는 나이 정보가 없거나 연령 미달이어도 본인 확인 후 가능해야 한다.
            if (expectedSubject == null) requireMinimumAge(profile.birthyear(), profile.birthday());
            return new OAuthProfile(profile.id(), profile.nickname(), null);
        } catch (HttpClientErrorException exception) {
            throw new AuthException(AuthError.INVALID_CREDENTIALS);
        } catch (RestClientException exception) {
            throw new AuthException(AuthError.OAUTH_PROVIDER_UNAVAILABLE);
        }
    }

    private void requireMinimumAge(String birthyear, String birthday) {
        // 공급자 생년 정보는 이 요청의 메모리에서만 확인한다. 회원 DTO·DB·로그로 전달하지 않는다.
        if (birthyear == null || !birthyear.matches("[0-9]{4}")
                || birthday == null || !birthday.matches("[0-9]{2}-[0-9]{2}")) {
            throw new AuthException(AuthError.AGE_VERIFICATION_REQUIRED);
        }
        java.time.LocalDate birthDate;
        try {
            birthDate = java.time.LocalDate.parse(birthyear + "-" + birthday,
                    java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (java.time.DateTimeException exception) {
            throw new AuthException(AuthError.AGE_VERIFICATION_REQUIRED);
        }
        var today = java.time.LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Seoul")));
        if (birthDate.getYear() < 1 || birthDate.isAfter(today)) {
            throw new AuthException(AuthError.AGE_VERIFICATION_REQUIRED);
        }
        if (java.time.Period.between(birthDate, today).getYears() < 14) {
            throw new AuthException(AuthError.AGE_RESTRICTED);
        }
    }

    private MultiValueMap<String, String> tokenRequest(String authorizationCode, String state) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("redirect_uri", properties.redirectUri());
        form.add("code", authorizationCode);
        form.add("state", state);
        return form;
    }

    private void requireClientConfiguration() {
        if (isBlank(properties.clientId()) || isBlank(properties.clientSecret()) || isBlank(properties.redirectUri())) {
            throw new AuthException(AuthError.OAUTH_PROVIDER_UNAVAILABLE);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private int characterCount(String value) {
        return value.codePointCount(0, value.length());
    }

    private record NaverTokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("error") String error) {
    }

    private record NaverProfileResponse(@JsonProperty("resultcode") String resultCode, NaverProfileFields response) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NaverProfileFields(
            String id,
            String nickname,
            String birthyear,
            String birthday) {
        @Override public String toString() { return "NaverProfileFields[personalData=redacted]"; }
    }
}
