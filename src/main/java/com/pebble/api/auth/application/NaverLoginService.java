package com.pebble.api.auth.application;

import com.pebble.api.auth.domain.AuthError;
import com.pebble.api.auth.domain.AuthException;
import com.pebble.api.auth.infrastructure.naver.NaverOAuthGateway.NaverProfile;
import com.pebble.api.auth.infrastructure.naver.NaverOAuthGateway;
import com.pebble.api.auth.infrastructure.naver.NaverOAuthProperties;
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
public class NaverLoginService {

    private final NaverOAuthGateway naverOAuthGateway;
    private final OAuthStateStore stateStore;
    private final NaverOAuthProperties naverProperties;
    private final OAuthMemberService memberService;
    private final UserSessionService sessions;
    private final MemberWithdrawalService withdrawals;

    public AuthorizationGrant beginAuthorization() {
        String state = stateStore.issue();
        return new AuthorizationGrant(naverOAuthGateway.authorizationUrl(state), state, naverProperties.stateTtl());
    }

    public LoginGrant login(String authorizationCode, String state, String cookieState) {
        NaverProfile profile = authenticate(authorizationCode, state, cookieState);
        Member member = memberService.resolve(OAuthProvider.NAVER, profile.subject(),
                profile.nickname(), profile.profileImageUrl());
        // 공급자 통신·계정 연결이 끝난 뒤 회원 읽기 잠금으로 최신 상태 확인과 발급을 조정한다.
        var grant = sessions.login(member.getId());
        return new LoginGrant(grant.accessToken(), grant.refreshToken(), grant.cookieTtl(), grant.member());
    }

    public void cancelWithdrawal(String authorizationCode, String state, String cookieState) {
        // 외부 공급자 통신은 DB 잠금 밖에서 수행하고 기존 계정만 취소한다.
        NaverProfile profile = authenticate(authorizationCode, state, cookieState);
        withdrawals.cancel(OAuthProvider.NAVER, profile.subject());
    }

    private NaverProfile authenticate(String authorizationCode, String state, String cookieState) {
        if (isBlank(state) || isBlank(cookieState) || !MessageDigest.isEqual(
                state.getBytes(StandardCharsets.UTF_8), cookieState.getBytes(StandardCharsets.UTF_8))) {
            throw new AuthException(AuthError.INVALID_OAUTH_STATE);
        }
        if (!stateStore.consume(state)) {
            throw new AuthException(AuthError.INVALID_OAUTH_STATE);
        }

        return naverOAuthGateway.authenticate(authorizationCode, state);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record AuthorizationGrant(String authorizationUrl, String state, Duration stateTtl) {
    }

    public record LoginGrant(String accessToken, String refreshToken, Duration refreshCookieTtl, Member member) {
    }
}
