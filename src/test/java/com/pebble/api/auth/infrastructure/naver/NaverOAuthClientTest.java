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
                Duration.ofMinutes(5), null);
        server.expect(requestTo(properties.tokenUri()))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("state=verified-state")))
                .andRespond(withSuccess("{\"access_token\":\"provider-token\",\"token_type\":\"bearer\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(properties.profileUri()))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer provider-token"))
                .andRespond(withSuccess(
                        "{\"resultcode\":\"00\",\"response\":{\"id\":\"naver-subject\",\"birthyear\":\"2000\",\"birthday\":\"01-01\",\"email\":\"unused@example.test\",\"name\":\"unused-name\",\"nickname\":\"pebble\",\"profile_image\":\"https://example.com/avatar.webp\"}}",
                        MediaType.APPLICATION_JSON));

        OAuthProviderClient gateway = new NaverOAuthClient(builder.build(), properties);
        OAuthProviderClient.OAuthProfile profile = gateway.authenticate("authorization-code", "verified-state");

        assertThat(profile.toString()).doesNotContain("unused@example.test", "unused-name", "2000", "01-01", "https://example.com/avatar.webp");
        assertThat(profile.subject()).isEqualTo("naver-subject");
        assertThat(profile.nickname()).isEqualTo("pebble");
        assertThat(profile.profileImageUrl()).isNull();
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
                "{\"resultcode\":\"00\",\"response\":{\"id\":\"subject\",\"birthyear\":\"2000\",\"birthday\":\"01-01\",\"nickname\":" + nicknameJson + "}}",
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


    @Test
    void loginDoesNotRequireBirthYearOrBirthday() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = properties();
        expectProfile(server, properties);
        var profile = new NaverOAuthClient(builder.build(), properties).authenticate("code", "state");
        assertThat(profile.subject()).isEqualTo("subject");
        assertThat(profile.serviceAgreement()).isNull();
        assertThat(profile.ageRestrictionApplied()).isFalse();
        server.verify();
    }

    @ParameterizedTest
    @MethodSource("providerAgreements")
    void onlyBothRequiredTermsForThisClientProduceVerifiedAgreement(String json, boolean accepted) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = plusProperties("2026-10-22", true);
        expectProfile(server, properties);
        server.expect(requestTo("https://openapi.naver.com/v1/nid/agreement"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer provider-token"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        var profile = new NaverOAuthClient(builder.build(), properties).authenticate("code", "state");
        assertThat(profile.serviceAgreement() != null).isEqualTo(accepted);
        assertThat(profile.ageRestrictionApplied()).isEqualTo(accepted);
        if (accepted) assertThat(profile.serviceAgreement().termsVersion()).isEqualTo("2026-10-22");
        assertThat(profile.toString()).doesNotContain("provider-token", "agreementInfos", "10/22");
        server.verify();
    }

    @Test
    void agreementOutageFallsBackToPebbleConsentWithoutBreakingLogin() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = plusProperties("2026-10-22", true);
        expectProfile(server, properties);
        server.expect(requestTo("https://openapi.naver.com/v1/nid/agreement")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(new NaverOAuthClient(builder.build(), properties).authenticate("code", "state").serviceAgreement()).isNull();
        server.verify();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"old,true", "2026-10-22,false"})
    void staleVersionOrMissingProviderAgeRestrictionCannotSkipPebbleConsent(String version, boolean ageRestricted) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = plusProperties(version, ageRestricted);
        expectProfile(server, properties);
        assertThat(new NaverOAuthClient(builder.build(), properties).authenticate("code", "state").serviceAgreement()).isNull();
        server.verify();
    }

    private static void expectProfile(MockRestServiceServer server, NaverOAuthProperties properties) {
        server.expect(requestTo(properties.tokenUri())).andRespond(withSuccess(
                "{\"access_token\":\"provider-token\",\"token_type\":\"bearer\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(properties.profileUri())).andRespond(withSuccess(
                "{\"resultcode\":\"00\",\"response\":{\"id\":\"subject\",\"nickname\":\"pebble\"}}", MediaType.APPLICATION_JSON));
    }
    private NaverOAuthProperties plusProperties(String version, boolean ageRestricted) {
        var p = properties();
        return new NaverOAuthProperties(p.clientId(), p.clientSecret(), p.redirectUri(), p.authorizationUri(),
                p.tokenUri(), p.profileUri(), p.stateTtl(),
                new NaverOAuthProperties.Plus(true, ageRestricted, "TERMS_20261022", "PRIVACY_20261022", version, version));
    }
    private static Stream<org.junit.jupiter.params.provider.Arguments> providerAgreements() {
        String terms = "{\"termCode\":\"TERMS_20261022\",\"clientId\":\"client-id\",\"agreeDate\":\"11:00:00.000 AM 10/22/2026\"}";
        String privacy = terms.replace("TERMS_20261022", "PRIVACY_20261022");
        return Stream.of(
            org.junit.jupiter.params.provider.Arguments.of("{\"result\":\"success\",\"agreementInfos\":["+terms+","+privacy+"]}", true),
            org.junit.jupiter.params.provider.Arguments.of("{\"result\":\"success\",\"agreementInfos\":["+terms+"]}", false),
            org.junit.jupiter.params.provider.Arguments.of("{\"result\":\"success\",\"agreementInfos\":["+terms+","+terms+"]}", false),
            org.junit.jupiter.params.provider.Arguments.of("{\"result\":\"success\",\"agreementInfos\":["+terms+","+privacy.replace("client-id", "another-client")+"]}", false),
            org.junit.jupiter.params.provider.Arguments.of(("{\"result\":\"success\",\"agreementInfos\":["+terms+","+privacy+"]}").replace("_20261022", "_20261021"), false),
            org.junit.jupiter.params.provider.Arguments.of("{\"result\":\"failure\",\"agreementInfos\":["+terms+","+privacy+"]}", false),
            org.junit.jupiter.params.provider.Arguments.of("{\"result\":\"success\",\"agreementInfos\":null}", false),
            org.junit.jupiter.params.provider.Arguments.of("{\"result\":\"success\",\"agreementInfos\":[null]}", false),
            org.junit.jupiter.params.provider.Arguments.of("{\"result\":\"success\",\"agreementInfos\":["+terms+","+privacy.replace("11:00:00.000 AM 10/22/2026", "")+"]}", false));
    }

    private static Stream<String> optionalNicknames() {
        return Stream.of(null, "😀".repeat(20));
    }

    private NaverOAuthProperties properties() {
        return new NaverOAuthProperties("client-id", "client-secret", "https://app.example/auth/naver/callback",
                "https://nid.naver.com/oauth2.0/authorize", "https://nid.naver.com/oauth2.0/token",
                "https://openapi.naver.com/v1/nid/me", Duration.ofMinutes(5), null);
    }
}
