package com.pebble.api.auth.infrastructure.naver;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pebble.api.auth.domain.AuthError;
import com.pebble.api.auth.domain.AuthException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
public class RestClientNaverOAuthGateway implements NaverOAuthGateway {

    private final RestClient restClient;
    private final NaverOAuthProperties properties;

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
    public NaverProfile authenticate(String authorizationCode, String state) {
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
                    || (profile.nickname() != null && characterCount(profile.nickname()) > 30)
                    || (profile.profileImage() != null && characterCount(profile.profileImage()) > 2048)) {
                throw new AuthException(AuthError.OAUTH_PROVIDER_UNAVAILABLE);
            }
            return new NaverProfile(profile.id(), profile.nickname(), profile.profileImage());
        } catch (HttpClientErrorException exception) {
            throw new AuthException(AuthError.INVALID_CREDENTIALS);
        } catch (RestClientException exception) {
            throw new AuthException(AuthError.OAUTH_PROVIDER_UNAVAILABLE);
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

    private record NaverProfileFields(
            String id,
            String nickname,
            @JsonProperty("profile_image") String profileImage) {
    }
}
