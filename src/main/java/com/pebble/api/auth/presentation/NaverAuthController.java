package com.pebble.api.auth.presentation;

import com.pebble.api.auth.application.NaverLoginService.AuthorizationGrant;
import com.pebble.api.auth.application.NaverLoginService.LoginGrant;
import com.pebble.api.auth.application.NaverLoginService.LoginResponse;
import com.pebble.api.auth.application.NaverLoginService;
import com.pebble.api.global.presentation.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/naver")
public class NaverAuthController {

    static final String STATE_COOKIE = "naver_oauth_state";
    static final String REFRESH_COOKIE = "refresh_token";
    private static final String COOKIE_PATH = "/api/v1/auth";

    private final NaverLoginService naverLoginService;

    public NaverAuthController(NaverLoginService naverLoginService) {
        this.naverLoginService = naverLoginService;
    }

    @PostMapping("/authorization")
    public ResponseEntity<ApiResponse<AuthorizationResponse>> beginAuthorization() {
        AuthorizationGrant grant = naverLoginService.beginAuthorization();
        ResponseCookie stateCookie = ResponseCookie.from(STATE_COOKIE, grant.state())
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(COOKIE_PATH + "/naver")
                .maxAge(grant.stateTtl())
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, stateCookie.toString())
                .body(ApiResponse.of(new AuthorizationResponse(grant.authorizationUrl())));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody NaverLoginRequest request,
            @CookieValue(name = STATE_COOKIE, required = false) String cookieState) {
        LoginGrant grant = naverLoginService.login(request.authorizationCode(), request.state(), cookieState);
        ResponseCookie expiredStateCookie = ResponseCookie.from(STATE_COOKIE, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(COOKIE_PATH + "/naver")
                .maxAge(Duration.ZERO)
                .build();
        ResponseCookie refreshCookie = ResponseCookie.from(REFRESH_COOKIE, grant.refreshToken())
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(COOKIE_PATH)
                .maxAge(grant.refreshCookieTtl())
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredStateCookie.toString(), refreshCookie.toString())
                .body(ApiResponse.of(grant.response()));
    }

    public record AuthorizationResponse(String authorizationUrl) {
    }

    public record NaverLoginRequest(
            @NotBlank @Size(max = 4096) String authorizationCode,
            @NotBlank @Size(max = 256) String state) {
    }
}
