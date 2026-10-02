package com.pebble.api.auth.presentation;

import com.pebble.api.auth.application.NaverLoginService.AuthorizationGrant;
import com.pebble.api.auth.application.NaverLoginService.LoginGrant;
import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.auth.application.NaverLoginService;
import com.pebble.api.auth.presentation.dto.NaverLoginResponse;
import com.pebble.api.auth.presentation.dto.NaverLoginResponse.MemberResponse;
import com.pebble.api.auth.presentation.dto.NaverWithdrawalRequest;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.global.presentation.response.ApiResponse;
import com.pebble.api.member.domain.Member;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/naver")
@RequiredArgsConstructor
public class NaverAuthController {

    static final String STATE_COOKIE = "naver_oauth_state";
    static final String REFRESH_COOKIE = "refresh_token";
    private static final String COOKIE_PATH = "/api/v1/auth";

    private final NaverLoginService naverLoginService;
    private final ObjectMapper mapper;

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
    public ResponseEntity<ApiResponse<NaverLoginResponse>> login(
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
                .body(ApiResponse.of(new NaverLoginResponse(
                        grant.accessToken(),
                        "Bearer",
                        AccessTokenService.EXPIRES_IN_SECONDS,
                        toMemberResponse(grant.member()))));
    }

    @PostMapping(value = "/withdrawal/cancel", consumes = "application/json")
    public ResponseEntity<ApiResponse<WithdrawalCancellationResponse>> cancelWithdrawal(
            @RequestBody String json,
            @CookieValue(name = STATE_COOKIE, required = false) String cookieState,
            HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        NaverWithdrawalRequest body;
        try {
            JsonNode tree = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
            body = NaverWithdrawalRequest.parse(tree);
        } catch (JsonProcessingException exception) {
            throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        }
        naverLoginService.cancelWithdrawal(body.authorizationCode(), body.state(), cookieState);
        ResponseCookie expiredStateCookie = ResponseCookie.from(STATE_COOKIE, "")
                .httpOnly(true).secure(true).sameSite("Lax")
                .path(COOKIE_PATH + "/naver").maxAge(Duration.ZERO).build();
        ResponseCookie expiredRefreshCookie = ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true).secure(true).sameSite("Lax")
                .path(COOKIE_PATH).maxAge(Duration.ZERO).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredStateCookie.toString(), expiredRefreshCookie.toString())
                .body(ApiResponse.of(new WithdrawalCancellationResponse("ACTIVE")));
    }

    private MemberResponse toMemberResponse(Member member) {
        return new MemberResponse(
                Long.toString(member.getId()),
                member.getNickname(),
                member.getProfileImageUrl(),
                member.getStatus().name(),
                "USER", member.getBlogName(), member.getHandle(), member.isProfileCompleted());
    }

    public record AuthorizationResponse(String authorizationUrl) {
    }

    public record WithdrawalCancellationResponse(String status) {
    }

    public record NaverLoginRequest(
            @NotBlank @Size(max = 4096) String authorizationCode,
            @NotBlank @Size(max = 256) String state) {
    }
}
