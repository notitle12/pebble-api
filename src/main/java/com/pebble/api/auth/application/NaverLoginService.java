package com.pebble.api.auth.application;

import com.pebble.api.auth.application.UserRefreshTokenService.IssuedRefreshToken;
import com.pebble.api.auth.domain.AuthError;
import com.pebble.api.auth.domain.AuthException;
import com.pebble.api.auth.infrastructure.naver.NaverOAuthGateway.NaverProfile;
import com.pebble.api.auth.infrastructure.naver.NaverOAuthGateway;
import com.pebble.api.auth.infrastructure.naver.NaverOAuthProperties;
import com.pebble.api.auth.infrastructure.redis.OAuthStateStore;
import com.pebble.api.member.application.OAuthMemberService;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.domain.OAuthProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class NaverLoginService {

    private final NaverOAuthGateway naverOAuthGateway;
    private final OAuthStateStore stateStore;
    private final NaverOAuthProperties naverProperties;
    private final OAuthMemberService memberService;
    private final AccessTokenService accessTokenService;
    private final UserRefreshTokenService refreshTokenService;
    private final Clock clock;

    public NaverLoginService(
            NaverOAuthGateway naverOAuthGateway,
            OAuthStateStore stateStore,
            NaverOAuthProperties naverProperties,
            OAuthMemberService memberService,
            AccessTokenService accessTokenService,
            UserRefreshTokenService refreshTokenService,
            Clock clock) {
        this.naverOAuthGateway = naverOAuthGateway;
        this.stateStore = stateStore;
        this.naverProperties = naverProperties;
        this.memberService = memberService;
        this.accessTokenService = accessTokenService;
        this.refreshTokenService = refreshTokenService;
        this.clock = clock;
    }

    public AuthorizationGrant beginAuthorization() {
        String state = stateStore.issue();
        return new AuthorizationGrant(naverOAuthGateway.authorizationUrl(state), state, naverProperties.stateTtl());
    }

    public LoginGrant login(String authorizationCode, String state, String cookieState) {
        if (isBlank(state) || isBlank(cookieState) || !MessageDigest.isEqual(
                state.getBytes(StandardCharsets.UTF_8), cookieState.getBytes(StandardCharsets.UTF_8))) {
            throw new AuthException(AuthError.INVALID_OAUTH_STATE);
        }
        if (!stateStore.consume(state)) {
            throw new AuthException(AuthError.INVALID_OAUTH_STATE);
        }

        NaverProfile profile = naverOAuthGateway.authenticate(authorizationCode, state);
        Member member = memberService.resolve(OAuthProvider.NAVER, profile.subject(),
                profile.nickname(), profile.profileImageUrl());
        if (member.getStatus() == MemberStatus.WITHDRAWAL_PENDING) {
            throw new AuthException(AuthError.WITHDRAWAL_PENDING);
        }
        if (member.getStatus() == MemberStatus.SUSPENDED) {
            throw new AuthException(AuthError.ACCOUNT_SUSPENDED);
        }
        Instant now = clock.instant();
        String accessToken = accessTokenService.issueForMember(member.getId(), now);
        IssuedRefreshToken refreshToken = refreshTokenService.issue(member.getId(), now);
        return new LoginGrant(
                new LoginResponse(
                        accessToken,
                        "Bearer",
                        AccessTokenService.EXPIRES_IN_SECONDS,
                        new MemberResponse(
                                Long.toString(member.getId()),
                                member.getNickname(),
                                member.getProfileImageUrl(),
                                member.getStatus().name(),
                                "USER")),
                refreshToken.value(),
                Duration.between(now, refreshToken.idleExpiresAt()));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record AuthorizationGrant(String authorizationUrl, String state, Duration stateTtl) {
    }

    public record LoginGrant(LoginResponse response, String refreshToken, Duration refreshCookieTtl) {
    }

    public record LoginResponse(
            String accessToken,
            String tokenType,
            long accessTokenExpiresIn,
            MemberResponse member) {
    }

    public record MemberResponse(String id, String nickname, String profileImageUrl, String status, String role) {
    }
}
