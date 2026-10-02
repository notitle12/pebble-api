package com.pebble.api.auth.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.auth.application.AdminAuthRateLimiter;
import com.pebble.api.auth.application.AdminRefreshTokenService;
import com.pebble.api.auth.application.AdminSessionService;
import com.pebble.api.auth.domain.AuthException;
import com.pebble.api.auth.infrastructure.AdminAuthAudit;
import com.pebble.api.auth.presentation.dto.AdminLoginRequest;
import com.pebble.api.auth.presentation.dto.AdminLoginResponse;
import com.pebble.api.auth.presentation.dto.RefreshResponse;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.global.presentation.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/auth")
@RequiredArgsConstructor
public class AdminSessionController {
    public static final String REFRESH_COOKIE = "admin_refresh_token";
    private final AdminSessionService sessions;
    private final AdminRefreshTokenService refreshTokens;
    private final AdminAuthRateLimiter limits;
    private final AdminAuthAudit audit;
    private final ObjectMapper mapper;

    @PostMapping(value = "/login", consumes = "application/json")
    public ResponseEntity<ApiResponse<AdminLoginResponse>> login(@RequestBody String json, HttpServletRequest request) {
        noQuery(request);
        AdminLoginRequest body;
        try {
            JsonNode tree = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
            body = AdminLoginRequest.parse(tree);
        } catch (JsonProcessingException exception) { throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST); }
        try {
            limits.login(body.loginId(), request.getRemoteAddr());
            var grant = sessions.login(body.loginId(), body.password());
            audit.record("LOGIN", "SUCCESS", grant.account().getId(), request);
            return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(grant.tokens().refreshToken(), grant.tokens().cookieTtl()).toString())
                    .body(ApiResponse.of(AdminLoginResponse.from(grant)));
        } catch (AuthException exception) {
            audit.record("LOGIN", exception.error().name(), null, request);
            throw exception;
        } catch (org.springframework.dao.DataAccessException exception) {
            audit.record("LOGIN", "BACKEND_FAILURE", null, request);
            throw exception;
        }
    }

    @PostMapping("/token/refresh")
    public ResponseEntity<ApiResponse<RefreshResponse>> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String token, HttpServletRequest request) {
        noPayload(request);
        Long adminId = null;
        try {
            limits.refreshIp(request.getRemoteAddr());
            var snapshot = refreshTokens.find(token);
            adminId = snapshot.adminId();
            limits.refresh(adminId, request.getRemoteAddr());
            var grant = sessions.refresh(snapshot);
            audit.record("REFRESH", "SUCCESS", adminId, request);
            return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(grant.refreshToken(), grant.cookieTtl()).toString())
                    .body(ApiResponse.of(new RefreshResponse(grant.accessToken(), "Bearer", AccessTokenService.EXPIRES_IN_SECONDS)));
        } catch (AuthException exception) {
            audit.record("REFRESH", exception.error().name(), adminId, request);
            throw exception;
        } catch (org.springframework.dao.DataAccessException exception) {
            audit.record("REFRESH", "BACKEND_FAILURE", adminId, request);
            throw exception;
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String token,
                                       HttpServletRequest request) {
        noPayload(request);
        Long adminId = null;
        try { adminId = refreshTokens.find(token).adminId(); }
        catch (AuthException exception) { /* 알 수 없는 쿠키도 삭제한다. */ }
        try { sessions.logout(token); }
        catch (org.springframework.dao.DataAccessException exception) {
            audit.record("LOGOUT", "BACKEND_FAILURE", adminId, request);
            throw exception;
        }
        audit.record("LOGOUT", "SUCCESS", adminId, request);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    private void noQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
    }
    private void noPayload(HttpServletRequest request) {
        noQuery(request);
        try {
            if (request.getInputStream().read() != -1) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        } catch (java.io.IOException exception) { throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST); }
    }
    private ResponseCookie cookie(String token, Duration ttl) {
        return ResponseCookie.from(REFRESH_COOKIE, token).httpOnly(true).secure(true).sameSite("Lax")
                .path("/api/v1/admin/auth").maxAge(ttl).build();
    }
}
