package com.pebble.api.auth.application;

import com.pebble.api.admin.application.AdminAccountQueryService;
import com.pebble.api.admin.domain.AdminAccount;
import com.pebble.api.admin.domain.AdminStatus;
import com.pebble.api.auth.domain.AuthError;
import com.pebble.api.auth.domain.AuthException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
// PostgreSQL 행 잠금 조회는 읽기 전용 트랜잭션에서 실행할 수 없다.
@Transactional
public class AdminSessionService {
    private final AdminAccountQueryService accounts;
    private final AdminRefreshTokenService refreshTokens;
    private final AccessTokenService accessTokens;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final String dummyPasswordHash;

    public AdminSessionService(AdminAccountQueryService accounts, AdminRefreshTokenService refreshTokens,
                               AccessTokenService accessTokens, PasswordEncoder passwords, Clock clock) {
        this.accounts = accounts;
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.passwords = passwords;
        this.clock = clock;
        // 존재하지 않는 계정도 동일한 해시 검증 비용을 지불한다.
        this.dummyPasswordHash = passwords.encode(UUID.randomUUID().toString());
    }

    public LoginGrant login(String loginId, String password) {
        AdminAccount account = accounts.findForLogin(loginId).orElse(null);
        boolean matches = passwords.matches(password, account == null ? dummyPasswordHash : account.getPasswordHash());
        if (!matches || account == null || account.getStatus() != AdminStatus.ACTIVE)
            throw new AuthException(AuthError.INVALID_CREDENTIALS);
        var now = clock.instant();
        String sid = UUID.randomUUID().toString();
        String accessToken = accessTokens.issueForAdmin(account.getId(), account.getRole().name(), sid, now);
        var refresh = refreshTokens.issue(account.getId(), account.getRole().name(), sid, now);
        return new LoginGrant(account, new RefreshGrant(accessToken, refresh.value(), Duration.between(now, refresh.idleExpiresAt())));
    }

    public RefreshGrant refresh(String token) {
        return refresh(refreshTokens.find(token));
    }

    public RefreshGrant refresh(AdminRefreshTokenService.TokenSnapshot snapshot) {
        AdminAccount account = accounts.findForRefresh(snapshot.adminId()).orElse(null);
        if (account == null || account.getStatus() != AdminStatus.ACTIVE || !account.getRole().name().equals(snapshot.role())) {
            refreshTokens.revokeAll(snapshot.adminId());
            throw new AuthException(AuthError.INVALID_REFRESH_TOKEN);
        }
        var now = clock.instant();
        String accessToken = accessTokens.issueForAdmin(account.getId(), snapshot.role(), snapshot.sessionId(), now);
        var refresh = refreshTokens.rotate(snapshot, now);
        return new RefreshGrant(accessToken, refresh.value(), Duration.between(now, refresh.idleExpiresAt()));
    }

    public void logout(String token) { refreshTokens.logout(token); }

    public boolean isAccessSessionActive(long adminId, String role, String sid) {
        if (!accounts.findActive(adminId, role)) {
            refreshTokens.revokeAll(adminId);
            return false;
        }
        return refreshTokens.isSessionActive(adminId, role, sid, clock.instant());
    }

    public record LoginGrant(AdminAccount account, RefreshGrant tokens) {
        @Override public String toString() { return "LoginGrant[redacted]"; }
    }
    public record RefreshGrant(String accessToken, String refreshToken, Duration cookieTtl) {
        @Override public String toString() { return "RefreshGrant[redacted]"; }
    }
}
