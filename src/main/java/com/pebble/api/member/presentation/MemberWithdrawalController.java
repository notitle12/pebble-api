package com.pebble.api.member.presentation;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.global.presentation.response.ApiResponse;
import com.pebble.api.auth.application.OAuthLoginService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/members/me")
@RequiredArgsConstructor
public class MemberWithdrawalController {

    private static final String MEMBER_SUBJECT_PREFIX = "member:";
    private final OAuthLoginService oauth;

    @DeleteMapping
    public ResponseEntity<ApiResponse<AuthorizationResponse>> request(
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        rejectPayloadOrQuery(request);
        long memberId = Long.parseLong(jwt.getSubject().substring(MEMBER_SUBJECT_PREFIX.length()));
        var grant = oauth.beginWithdrawal(memberId);
        ResponseCookie stateCookie = ResponseCookie.from("naver_oauth_state", grant.state())
                .httpOnly(true).secure(true).sameSite("Lax")
                .path("/api/v1/auth/naver").maxAge(grant.stateTtl()).build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, stateCookie.toString())
                .body(ApiResponse.of(new AuthorizationResponse(grant.authorizationUrl())));
    }

    private void rejectPayloadOrQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        try {
            if (request.getInputStream().read() != -1) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        } catch (IOException exception) {
            throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        }
    }

    public record AuthorizationResponse(String authorizationUrl) {
    }
}
