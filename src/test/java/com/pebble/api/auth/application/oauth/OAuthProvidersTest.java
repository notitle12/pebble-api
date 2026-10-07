package com.pebble.api.auth.application.oauth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import com.pebble.api.auth.domain.AuthException;
import com.pebble.api.member.domain.OAuthProvider;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class OAuthProvidersTest {
    @Test
    void rejectsDuplicateRegistrationAndUnregisteredProvider() {
        var registration = new OAuthProviderRegistration(OAuthProvider.NAVER, Duration.ofMinutes(5), mock(OAuthProviderClient.class));
        assertThatThrownBy(() -> new OAuthProviders(List.of(registration, registration))).isInstanceOf(IllegalStateException.class);
        var providers = new OAuthProviders(List.of(registration));
        assertThatThrownBy(() -> providers.require(OAuthProvider.GOOGLE)).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> providers.require(null)).isInstanceOf(AuthException.class);
    }

    @Test
    void rejectsNonExpiringStateConfiguration() {
        assertThatThrownBy(() -> new OAuthProviderRegistration(OAuthProvider.NAVER, Duration.ZERO, mock(OAuthProviderClient.class)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OAuthProviderRegistration(OAuthProvider.NAVER, Duration.ofSeconds(-1), mock(OAuthProviderClient.class)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
