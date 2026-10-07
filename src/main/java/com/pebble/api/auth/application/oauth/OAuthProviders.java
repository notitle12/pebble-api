package com.pebble.api.auth.application.oauth;

import com.pebble.api.auth.domain.AuthError;
import com.pebble.api.auth.domain.AuthException;
import com.pebble.api.member.domain.OAuthProvider;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class OAuthProviders {
    private final Map<OAuthProvider, OAuthProviderRegistration> registrations;

    public OAuthProviders(List<OAuthProviderRegistration> registrations) {
        var providers = new EnumMap<OAuthProvider, OAuthProviderRegistration>(OAuthProvider.class);
        for (var registration : registrations) {
            if (providers.putIfAbsent(registration.provider(), registration) != null) {
                throw new IllegalStateException("Duplicate OAuth provider registration: " + registration.provider());
            }
        }
        this.registrations = Map.copyOf(providers);
    }

    public OAuthProviderRegistration require(OAuthProvider provider) {
        var registration = provider == null ? null : registrations.get(provider);
        if (registration == null) throw new AuthException(AuthError.OAUTH_PROVIDER_UNAVAILABLE);
        return registration;
    }
}
