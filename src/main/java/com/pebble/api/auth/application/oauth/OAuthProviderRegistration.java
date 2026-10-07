package com.pebble.api.auth.application.oauth;

import com.pebble.api.member.domain.OAuthProvider;
import java.time.Duration;
import java.util.Objects;

/** 설정된 공급자만 활성화한다. enum에 이름이 있다는 이유만으로 로그인은 허용하지 않는다. */
public record OAuthProviderRegistration(OAuthProvider provider, Duration stateTtl, OAuthProviderClient client) {
    public OAuthProviderRegistration {
        Objects.requireNonNull(provider);
        Objects.requireNonNull(client);
        Objects.requireNonNull(stateTtl);
        if (stateTtl.isZero() || stateTtl.isNegative()) throw new IllegalArgumentException("OAuth state TTL must be positive");
    }
}
