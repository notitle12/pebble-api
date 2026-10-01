package com.pebble.api.auth.application;

import com.pebble.api.auth.domain.AuthError;
import com.pebble.api.auth.domain.AuthException;
import com.pebble.api.auth.application.UserRefreshTokenService.IssuedRefreshToken;
import com.pebble.api.member.application.OAuthMemberService;
import com.pebble.api.member.domain.MemberStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserSessionService {

    private final UserRefreshTokenService refreshTokens;
    private final AccessTokenService accessTokens;
    private final OAuthMemberService members;
    private final Clock clock;

    public RefreshGrant refresh(String token) {
        var snapshot = refreshTokens.find(token);
        MemberStatus status = members.findStatus(snapshot.memberId()).orElse(null);
        if (status != MemberStatus.ACTIVE) {
            refreshTokens.revoke(snapshot);
            if (status == MemberStatus.SUSPENDED) {
                throw new AuthException(AuthError.ACCOUNT_SUSPENDED);
            }
            if (status == MemberStatus.WITHDRAWAL_PENDING) {
                throw new AuthException(AuthError.WITHDRAWAL_PENDING);
            }
            throw new AuthException(AuthError.INVALID_REFRESH_TOKEN);
        }
        Instant now = clock.instant();
        // 서명 실패로 기존 토큰만 소비되는 상황을 줄이도록 Access JWT를 먼저 준비한다.
        String accessToken = accessTokens.issueForMember(snapshot.memberId(), now);
        IssuedRefreshToken refresh = refreshTokens.rotate(snapshot, now);
        return new RefreshGrant(accessToken, refresh.value(), Duration.between(now, refresh.idleExpiresAt()));
    }

    public void logout(String token) {
        refreshTokens.logout(token);
    }

    public record RefreshGrant(String accessToken, String refreshToken, Duration cookieTtl) {
    }
}
