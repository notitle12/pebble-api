package com.pebble.api.auth.infrastructure.naver;

import com.pebble.api.auth.application.oauth.OAuthProviderClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.pebble.api.auth.domain.AuthError;
import com.pebble.api.auth.domain.AuthException;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class NaverOAuthClientTest {

    @Test
    void exchangesAuthorizationCodeAndLoadsNaverProfile() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NaverOAuthProperties properties = new NaverOAuthProperties(
                "client-id", "client-secret", "https://app.example/auth/naver/callback",
                "https://nid.naver.com/oauth2.0/authorize",
                "https://nid.naver.com/oauth2.0/token",
                "https://openapi.naver.com/v1/nid/me",
                Duration.ofMinutes(5));
        server.expect(requestTo(properties.tokenUri()))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("state=verified-state")))
                .andRespond(withSuccess("{\"access_token\":\"provider-token\",\"token_type\":\"bearer\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(properties.profileUri()))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer provider-token"))
                .andRespond(withSuccess(
                        "{\"resultcode\":\"00\",\"response\":{\"id\":\"naver-subject\",\"nickname\":\"pebble\",\"profile_image\":\"https://example.com/avatar.webp\"}}",
                        MediaType.APPLICATION_JSON));

        OAuthProviderClient gateway = new NaverOAuthClient(builder.build(), properties);
        OAuthProviderClient.OAuthProfile profile = gateway.authenticate("authorization-code", "verified-state");

        assertThat(profile.subject()).isEqualTo("naver-subject");
        assertThat(profile.nickname()).isEqualTo("pebble");
        assertThat(profile.profileImageUrl()).isEqualTo("https://example.com/avatar.webp");
        server.verify();
    }

    @ParameterizedTest
    @MethodSource("optionalNicknames")
    void acceptsMissingOptionalProfileOrUnicodeNickname(String nickname) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NaverOAuthProperties properties = properties();
        server.expect(requestTo(properties.tokenUri())).andRespond(withSuccess(
                "{\"access_token\":\"provider-token\",\"token_type\":\"bearer\"}", MediaType.APPLICATION_JSON));
        String nicknameJson = nickname == null ? "null" : "\"" + nickname + "\"";
        server.expect(requestTo(properties.profileUri())).andRespond(withSuccess(
                "{\"resultcode\":\"00\",\"response\":{\"id\":\"subject\",\"nickname\":" + nicknameJson + "}}",
                MediaType.APPLICATION_JSON));
        var profile = new NaverOAuthClient(builder.build(), properties).authenticate("code", "state");
        assertThat(profile.nickname()).isEqualTo(nickname);
        assertThat(profile.profileImageUrl()).isNull();
        server.verify();
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"BAD_REQUEST", "SERVICE_UNAVAILABLE"})
    void distinguishesInvalidCodeFromProviderOutage(HttpStatus status) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NaverOAuthProperties properties = properties();
        server.expect(requestTo(properties.tokenUri())).andRespond(withStatus(status));
        AuthException exception = catchThrowableOfType(AuthException.class,
                () -> new NaverOAuthClient(builder.build(), properties).authenticate("code", "state"));
        assertThat(exception.error()).isEqualTo(status.is4xxClientError()
                ? AuthError.INVALID_CREDENTIALS : AuthError.OAUTH_PROVIDER_UNAVAILABLE);
        server.verify();
    }

    @Test
    void withdrawalReauthChecksIdentityAndRevokesProviderTokenImmediately() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var properties = properties();
        server.expect(requestTo(properties.tokenUri())).andRespond(withSuccess(
                "{\"access_token\":\"provider-token\",\"token_type\":\"bearer\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(properties.profileUri())).andRespond(withSuccess(
                "{\"resultcode\":\"00\",\"response\":{\"id\":\"subject\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://nid.naver.com/oauth2.0/revoke"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("token=provider-token")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("token_type_hint=access_token")))
                .andRespond(withSuccess());
        var client = new NaverOAuthClient(builder.build(), properties);
        assertThat(client.withdrawalAuthorizationUrl("state")).contains("auth_type=reauthenticate");
        client.authenticateAndRevoke("code", "state", "subject");
        server.verify();
    }

    @Test
    void differentNaverAccountCannotRevokeOrWithdrawExpectedMember() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var properties = properties();
        server.expect(requestTo(properties.tokenUri())).andRespond(withSuccess(
                "{\"access_token\":\"provider-token\",\"token_type\":\"bearer\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(properties.profileUri())).andRespond(withSuccess(
                "{\"resultcode\":\"00\",\"response\":{\"id\":\"other-subject\"}}", MediaType.APPLICATION_JSON));
        var client = new NaverOAuthClient(builder.build(), properties);
        assertThat(catchThrowableOfType(AuthException.class,
                () -> client.authenticateAndRevoke("code", "state", "expected-subject")).error())
                .isEqualTo(AuthError.INVALID_CREDENTIALS);
        server.verify();
    }

    @Test
    void revocationFailureIsNotReportedAsCompletedWithdrawal() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var properties = properties();
        server.expect(requestTo(properties.tokenUri())).andRespond(withSuccess(
                "{\"access_token\":\"provider-token\",\"token_type\":\"bearer\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(properties.profileUri())).andRespond(withSuccess(
                "{\"resultcode\":\"00\",\"response\":{\"id\":\"subject\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://nid.naver.com/oauth2.0/revoke"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(catchThrowableOfType(AuthException.class,
                () -> new NaverOAuthClient(builder.build(), properties).authenticateAndRevoke("code", "state", "subject")).error())
                .isEqualTo(AuthError.OAUTH_PROVIDER_UNAVAILABLE);
        server.verify();
    }

    private static Stream<String> optionalNicknames() {
        return Stream.of(null, "😀".repeat(20));
    }

    private NaverOAuthProperties properties() {
        return new NaverOAuthProperties("client-id", "client-secret", "https://app.example/auth/naver/callback",
                "https://nid.naver.com/oauth2.0/authorize", "https://nid.naver.com/oauth2.0/token",
                "https://openapi.naver.com/v1/nid/me", Duration.ofMinutes(5));
    }
}
