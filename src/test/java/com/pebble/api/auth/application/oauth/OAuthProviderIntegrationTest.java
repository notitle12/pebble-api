package com.pebble.api.auth.application.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.pebble.api.auth.application.OAuthLoginService;
import com.pebble.api.auth.domain.AuthError;
import com.pebble.api.auth.domain.AuthException;
import com.pebble.api.member.domain.OAuthProvider;
import com.pebble.api.member.infrastructure.persistence.MemberOAuthIdentityRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(OAuthProviderIntegrationTest.TestProvider.class)
@Transactional
class OAuthProviderIntegrationTest extends AuthenticationTestSupport {
    @Autowired OAuthLoginService login;
    @Autowired MemberOAuthIdentityRepository identities;
    @MockitoBean(name = "naverOAuthClient") OAuthProviderClient naver;

    @Test
    void stateCannotCrossProvidersAndRemainsSingleUse() {
        var authorization = login.beginAuthorization(OAuthProvider.GOOGLE);
        assertThat(authorization.stateTtl()).isEqualTo(Duration.ofMinutes(2));
        assertThatThrownBy(() -> login.login(OAuthProvider.NAVER, "test-code", authorization.state(), authorization.state()))
                .isInstanceOfSatisfying(AuthException.class, ex -> assertThat(ex.error()).isEqualTo(AuthError.INVALID_OAUTH_STATE));
        verifyNoInteractions(naver);
        var grant = login.login(OAuthProvider.GOOGLE, "test-code", authorization.state(), authorization.state());
        assertThat(identities.findByProviderAndProviderSubject(OAuthProvider.GOOGLE, "same-subject")).isPresent();
        assertThat(grant.member().getId()).isNotNull();
        assertThatThrownBy(() -> login.login(OAuthProvider.GOOGLE, "test-code", authorization.state(), authorization.state()))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void sameSubjectInDifferentProvidersDoesNotMergeAccounts() {
        when(naver.authorizationUrl(org.mockito.ArgumentMatchers.anyString())).thenReturn("https://example.test/naver");
        when(naver.authenticate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new OAuthProviderClient.OAuthProfile("same-subject", "same-name", null));
        var naverState = login.beginAuthorization(OAuthProvider.NAVER);
        var naverMember = login.login(OAuthProvider.NAVER, "test-code", naverState.state(), naverState.state()).member();
        var googleState = login.beginAuthorization(OAuthProvider.GOOGLE);
        var googleMember = login.login(OAuthProvider.GOOGLE, "test-code", googleState.state(), googleState.state()).member();
        assertThat(naverMember.getId()).isNotEqualTo(googleMember.getId());
    }

    @Test
    void mismatchedCookieDoesNotConsumeValidState() {
        var state = login.beginAuthorization(OAuthProvider.GOOGLE);
        assertThatThrownBy(() -> login.login(OAuthProvider.GOOGLE, "test-code", state.state(), "wrong-cookie"))
                .isInstanceOf(AuthException.class);
        assertThat(login.login(OAuthProvider.GOOGLE, "test-code", state.state(), state.state()).member()).isNotNull();
    }

    @TestConfiguration
    static class TestProvider {
        @Bean
        OAuthProviderRegistration testGoogleRegistration() {
            return new OAuthProviderRegistration(OAuthProvider.GOOGLE, Duration.ofMinutes(2), new OAuthProviderClient() {
                public String authorizationUrl(String state) { return "https://example.test/google?state=" + state; }
                public OAuthProfile authenticate(String code, String state) { return new OAuthProfile("same-subject", "same-name", null); }
            });
        }
    }
}
