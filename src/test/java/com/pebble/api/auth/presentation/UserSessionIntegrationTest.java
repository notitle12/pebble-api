package com.pebble.api.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pebble.api.auth.application.UserRefreshTokenService;
import com.pebble.api.auth.application.UserRefreshTokenService.IssuedRefreshToken;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import com.pebble.api.auth.infrastructure.naver.NaverOAuthGateway;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserSessionIntegrationTest extends AuthenticationTestSupport {

    private static final String FRONTEND_ORIGIN = "http://localhost:3000";
    private static final String REFRESH_PATH = "/api/v1/auth/token/refresh";
    private static final String LOGOUT_PATH = "/api/v1/auth/logout";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private UserRefreshTokenService refreshTokens;

    @Autowired
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private NaverOAuthGateway naverOAuthGateway;

    @Test
    void refreshRotatesCookieAndRejectsReusedFamilyTokens() throws Exception {
        Cookie original = issueRefreshCookie(MemberStatus.ACTIVE);

        MvcResult result = mockMvc.perform(post(REFRESH_PATH).cookie(original).header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.accessTokenExpiresIn").value(900))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();

        String accessToken = jsonPathString(result, "accessToken");
        assertThat(jwtDecoder.decode(accessToken).getClaimAsString("token_type")).isEqualTo("access");
        String rotatedValue = cookieValue(result, "refresh_token");
        assertThat(rotatedValue).isNotEqualTo(original.getValue());
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .contains("refresh_token=", "HttpOnly", "Secure", "SameSite=Lax", "Path=/api/v1/auth");

        mockMvc.perform(post(REFRESH_PATH).cookie(original).header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
        mockMvc.perform(post(REFRESH_PATH).cookie(new Cookie("refresh_token", rotatedValue))
                        .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void logoutIsIdempotentExpiresCookieAndRevokesFamily() throws Exception {
        Cookie original = issueRefreshCookie(MemberStatus.ACTIVE);
        MvcResult refreshed = mockMvc.perform(post(REFRESH_PATH).cookie(original)
                        .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN))
                .andExpect(status().isOk()).andReturn();
        Cookie rotated = new Cookie("refresh_token", cookieValue(refreshed, "refresh_token"));

        mockMvc.perform(post(LOGOUT_PATH).cookie(rotated).header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString("Max-Age=0")));
        mockMvc.perform(post(REFRESH_PATH).cookie(rotated).header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
        mockMvc.perform(post(LOGOUT_PATH).cookie(rotated).header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN))
                .andExpect(status().isNoContent());
        mockMvc.perform(post(LOGOUT_PATH).cookie(new Cookie("refresh_token", "invalid"))
                        .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN))
                .andExpect(status().isNoContent());
    }

    @Test
    void rejectsMissingAndInvalidRefreshCookies() throws Exception {
        mockMvc.perform(post(REFRESH_PATH).header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
        mockMvc.perform(post(REFRESH_PATH).cookie(new Cookie("refresh_token", "invalid"))
                        .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void rejectsMissingNullForeignAndDuplicateOriginsWithTraceId() throws Exception {
        mockMvc.perform(post(REFRESH_PATH).cookie(issueRefreshCookie(MemberStatus.ACTIVE)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.traceId").isNotEmpty());
        expectForbiddenOrigin("null");
        expectForbiddenOrigin("https://untrusted.example");
        mockMvc.perform(post(REFRESH_PATH).cookie(issueRefreshCookie(MemberStatus.ACTIVE))
                        .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN, FRONTEND_ORIGIN))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.traceId").isNotEmpty());
        mockMvc.perform(post(LOGOUT_PATH).cookie(issueRefreshCookie(MemberStatus.ACTIVE))
                        .header(HttpHeaders.ORIGIN, "https://untrusted.example"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.traceId").isNotEmpty());
    }

    @Test
    void allowsConfiguredAndRejectsUntrustedCorsPreflightForRefreshAndLogout() throws Exception {
        for (String path : new String[]{REFRESH_PATH, LOGOUT_PATH}) {
            mockMvc.perform(options(path).header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND_ORIGIN))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
            mockMvc.perform(options(path).header(HttpHeaders.ORIGIN, "https://untrusted.example")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }
    }

    @Test
    void revokedMemberStatesRejectRefreshAndRevokeTheirTokenFamily() throws Exception {
        assertMemberStateRejectsRefresh(MemberStatus.SUSPENDED, 403, "ACCOUNT_SUSPENDED");
        assertMemberStateRejectsRefresh(MemberStatus.WITHDRAWAL_PENDING, 409, "WITHDRAWAL_PENDING");
    }

    @Test
    void deniesUnrelatedPaths() throws Exception {
        mockMvc.perform(get("/api/v1/unrelated"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    private void assertMemberStateRejectsRefresh(MemberStatus memberStatus, int statusCode, String errorCode)
            throws Exception {
        Cookie issued = issueRefreshCookie(memberStatus);
        mockMvc.perform(post(REFRESH_PATH).cookie(issued).header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN))
                .andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.error.code").value(errorCode));
        mockMvc.perform(post(REFRESH_PATH).cookie(issued).header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
    }

    private Cookie issueRefreshCookie(MemberStatus memberStatus) {
        Instant now = Instant.now();
        Member member = memberRepository.saveAndFlush(new Member("session-" + java.util.UUID.randomUUID().toString().substring(0, 12), null, memberStatus,
                memberStatus == MemberStatus.WITHDRAWAL_PENDING ? now : null,
                memberStatus == MemberStatus.WITHDRAWAL_PENDING ? now.plusSeconds(60) : null));
        IssuedRefreshToken issued = refreshTokens.issue(member.getId(), now);
        return new Cookie("refresh_token", issued.value());
    }

    private void expectForbiddenOrigin(String origin) throws Exception {
        mockMvc.perform(post(REFRESH_PATH).cookie(issueRefreshCookie(MemberStatus.ACTIVE))
                        .header(HttpHeaders.ORIGIN, origin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.traceId").isNotEmpty());
    }

    private String cookieValue(MvcResult result, String name) {
        String prefix = name + "=";
        String setCookie = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith(prefix))
                .findFirst().orElseThrow();
        return setCookie.substring(prefix.length(), setCookie.indexOf(';'));
    }

    @SuppressWarnings("unchecked")
    private String jsonPathString(MvcResult result, String field) throws Exception {
        var body = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(result.getResponse().getContentAsString(), java.util.Map.class);
        return (String) ((java.util.Map<String, Object>) body.get("data")).get(field);
    }
}
