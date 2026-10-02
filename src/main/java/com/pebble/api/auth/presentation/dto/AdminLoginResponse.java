package com.pebble.api.auth.presentation.dto;

import com.pebble.api.admin.domain.AdminAccount;
import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.auth.application.AdminSessionService.LoginGrant;

public record AdminLoginResponse(String accessToken, String tokenType, long accessTokenExpiresIn, AdminInfo admin) {
    public static AdminLoginResponse from(LoginGrant grant) {
        AdminAccount account = grant.account();
        return new AdminLoginResponse(grant.tokens().accessToken(), "Bearer", AccessTokenService.EXPIRES_IN_SECONDS,
                new AdminInfo(account.getId().toString(), account.getLoginId(), account.getRole().name(), account.getStatus().name()));
    }
    public record AdminInfo(String id, String loginId, String role, String status) { }
    @Override public String toString() { return "AdminLoginResponse[redacted]"; }
}
