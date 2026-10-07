package com.pebble.api.auth.application;

import com.pebble.api.auth.domain.AuthError;
import com.pebble.api.auth.domain.AuthException;
import com.pebble.api.auth.application.oauth.OAuthProviderClient.OAuthProfile;
import com.pebble.api.auth.application.oauth.OAuthProviders;
import com.pebble.api.auth.infrastructure.redis.OAuthStateStore;
import com.pebble.api.member.application.OAuthMemberService;
import com.pebble.api.member.application.MemberWithdrawalService;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.OAuthProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OAuthLoginService {

    private final OAuthProviders providers;
    private final OAuthStateStore stateStore;
    private final OAuthMemberService memberService;
    private final UserSessionService sessions;
    private final MemberWithdrawalService withdrawals;

    public AuthorizationGrant beginAuthorization(OAuthProvider provider) {
        var registration = providers.require(provider);
        String state = stateStore.issue(provider, registration.stateTtl());
        return new AuthorizationGrant(registration.client().authorizationUrl(state), state, registration.stateTtl());
    }

    public LoginGrant login(OAuthProvider provider, String authorizationCode, String state, String cookieState) {
        OAuthProfile profile = authenticate(provider, authorizationCode, state, cookieState);
        Member member = memberService.resolve(provider, profile.subject(),
                profile.nickname(), profile.profileImageUrl());
        // 공급자 통신·계정 연결이 끝난 뒤 회원 읽기 잠금으로 최신 상태 확인과 발급을 조정한다.
        var grant = sessions.login(member.getId());
        return new LoginGrant(grant.accessToken(), grant.refreshToken(), grant.cookieTtl(), grant.member());
    }

    public void cancelWithdrawal(OAuthProvider provider, String authorizationCode, String state, String cookieState) {
        // 외부 공급자 통신은 DB 잠금 밖에서 수행하고 기존 계정만 취소한다.
        OAuthProfile profile = authenticate(provider, authorizationCode, state, cookieState);
        withdrawals.cancel(provider, profile.subject());
    }

    private OAuthProfile authenticate(OAuthProvider provider, String authorizationCode, String state, String cookieState) {
        var registration = providers.require(provider);
        if (isBlank(state) || isBlank(cookieState) || !MessageDigest.isEqual(
                state.getBytes(StandardCharsets.UTF_8), cookieState.getBytes(StandardCharsets.UTF_8))) {
            throw new AuthException(AuthError.INVALID_OAUTH_STATE);
        }
        if (!stateStore.consume(provider, state)) {
            throw new AuthException(AuthError.INVALID_OAUTH_STATE);
        }

        return registration.client().authenticate(authorizationCode, state);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record AuthorizationGrant(String authorizationUrl, String state, Duration stateTtl) {
        @Override public String toString() { return "AuthorizationGrant[state=redacted]"; }
    }

    public record LoginGrant(String accessToken, String refreshToken, Duration refreshCookieTtl, Member member) {
        @Override public String toString() { return "LoginGrant[tokens=redacted]"; }
    }
}
