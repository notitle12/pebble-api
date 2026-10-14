package com.pebble.api.auth.presentation;

import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.auth.application.UserSessionService;
import com.pebble.api.auth.presentation.dto.RefreshResponse;
import com.pebble.api.global.presentation.response.ApiResponse;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class UserSessionController {

    private final UserSessionService sessions;

    @PostMapping("/token/refresh")
    public ResponseEntity<ApiResponse<RefreshResponse>> refresh(
            @CookieValue(name = NaverAuthController.REFRESH_COOKIE, required = false) String token) {
        var grant = sessions.refresh(token);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(grant.refreshToken(), grant.cookieTtl()).toString())
                .body(ApiResponse.of(new RefreshResponse(grant.accessToken(), "Bearer",
                        AccessTokenService.EXPIRES_IN_SECONDS)));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = NaverAuthController.REFRESH_COOKIE, required = false) String token) {
        sessions.logout(token);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    private ResponseCookie cookie(String token, Duration ttl) {
        return ResponseCookie.from(NaverAuthController.REFRESH_COOKIE, token)
                .httpOnly(true).secure(true).sameSite("Lax").path("/api/v1/auth").maxAge(ttl).build();
    }
}
