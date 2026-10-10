package com.pebble.api.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.auth.domain.AuthError;
import com.pebble.api.auth.domain.AuthException;
import com.pebble.api.auth.application.oauth.OAuthProviderClient.OAuthProfile;
import com.pebble.api.auth.application.oauth.OAuthProviderClient;
import com.pebble.api.auth.infrastructure.redis.RefreshTokenProperties;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberOAuthIdentity;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.domain.OAuthProvider;
import com.pebble.api.member.infrastructure.persistence.MemberOAuthIdentityRepository;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NaverAuthIntegrationTest extends AuthenticationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private MemberOAuthIdentityRepository identityRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RefreshTokenProperties refreshTokenProperties;

    @MockitoBean(name = "naverOAuthClient")
    private OAuthProviderClient naverOAuthGateway;

    @Test
    void firstLoginCreatesMemberAndReturnsPebbleTokens() throws Exception {
        when(naverOAuthGateway.authorizationUrl(anyString()))
                .thenAnswer(invocation -> "https://nid.naver.com/oauth2.0/authorize?state=" + invocation.getArgument(0));
        Cookie stateCookie = beginAuthorizationAndGetCookie();
        String state = stateCookie.getValue();
        when(naverOAuthGateway.authenticate("one-time-code", state))
                .thenReturn(new OAuthProfile("naver-id-1", "pebble", "https://example.com/profile.webp"));
        MvcResult result = login("one-time-code", state, stateCookie);

        Map<String, Object> data = dataFrom(result);
        assertThat(data.get("tokenType")).isEqualTo("Bearer");
        assertThat(data.get("accessTokenExpiresIn")).isEqualTo(900);
        @SuppressWarnings("unchecked")
        Map<String, Object> memberData = (Map<String, Object>) data.get("member");
        assertThat(memberData.get("nickname")).isEqualTo("pebble");
        assertThat(memberData.get("profileImageUrl")).isEqualTo("https://example.com/profile.webp");
        assertThat(memberData.get("role")).isEqualTo("USER");
        assertThat(memberRepository.count()).isEqualTo(1);
        assertThat(identityRepository.findByProviderAndProviderSubject(OAuthProvider.NAVER, "naver-id-1"))
                .isPresent();

        String accessToken = (String) data.get("accessToken");
        var jwt = jwtDecoder.decode(accessToken);
        assertThat(jwt.getIssuer().toString()).isEqualTo("https://pebble.local");
        assertThat(java.time.Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).toSeconds()).isEqualTo(900);
        assertThat(jwt.getSubject()).startsWith("member:");
        assertThat(jwt.getAudience()).contains("pebble-api");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("USER");
        assertThat(jwt.getClaimAsString("token_type")).isEqualTo("access");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("refreshToken");
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .anySatisfy(header -> assertThat(header)
                        .contains("refresh_token=")
                        .contains("HttpOnly")
                        .contains("Secure")
                        .contains("SameSite=Lax"));
        String refreshCookie = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith("refresh_token="))
                .findFirst().orElseThrow();
        assertThat(refreshCookie).contains("Max-Age=1209600", "Path=/api/v1/auth");
        String refreshToken = refreshCookie.substring("refresh_token=".length(), refreshCookie.indexOf(';'));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(refreshTokenProperties.pepperBase64()), "HmacSHA256"));
        String digest = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(refreshToken.getBytes(StandardCharsets.UTF_8)));
        String key = "pebble:auth:user:refresh-token:" + digest;
        Map<Object, Object> session = redisTemplate.opsForHash().entries(key);
        assertThat(session).containsEntry("subjectType", "USER").containsEntry("subjectId", memberData.get("id"));
        assertThat(session.values()).doesNotContain(refreshToken);
        Instant createdAt = Instant.parse((String) session.get("familyCreatedAt"));
        assertThat(Duration.between(createdAt, Instant.parse((String) session.get("idleExpiresAt"))))
                .isEqualTo(Duration.ofDays(14));
        assertThat(Duration.between(createdAt, Instant.parse((String) session.get("absoluteExpiresAt"))))
                .isEqualTo(Duration.ofDays(30));
        assertThat(redisTemplate.getExpire(key)).isBetween(1209500L, 1209600L);
    }

    @Test
    void existingMemberLogsInWithoutCreatingAnotherAccount() throws Exception {
        Member member = memberRepository.saveAndFlush(new Member("saved-name", null, MemberStatus.ACTIVE, null, null));
        identityRepository.saveAndFlush(new MemberOAuthIdentity(member, OAuthProvider.NAVER, "existing-subject"));
        when(naverOAuthGateway.authorizationUrl(anyString()))
                .thenAnswer(invocation -> "https://nid.naver.com/oauth2.0/authorize?state=" + invocation.getArgument(0));
        Cookie cookie = beginAuthorizationAndGetCookie();
        when(naverOAuthGateway.authenticate("code", cookie.getValue()))
                .thenReturn(new OAuthProfile("existing-subject", "changed-naver-name", null));
        Map<String, Object> data = dataFrom(login("code", cookie.getValue(), cookie));
        assertThat(data.get("member")).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) data.get("member")).get("id")).isEqualTo(member.getId().toString());
        assertThat(((Map<?, ?>) data.get("member")).get("nickname")).isEqualTo("saved-name");
        assertThat(memberRepository.count()).isEqualTo(1);
        assertThat(identityRepository.count()).isEqualTo(1);
    }

    @Test
    void createsMemberWhenOptionalNaverProfileIsAbsent() throws Exception {
        when(naverOAuthGateway.authorizationUrl(anyString()))
                .thenAnswer(invocation -> "https://nid.naver.com/oauth2.0/authorize?state=" + invocation.getArgument(0));
        Cookie cookie = beginAuthorizationAndGetCookie();
        when(naverOAuthGateway.authenticate("code", cookie.getValue()))
                .thenReturn(new OAuthProfile("no-profile-subject", null, null));
        Map<String, Object> data = dataFrom(login("code", cookie.getValue(), cookie));
        assertThat(((Map<?, ?>) data.get("member")).get("nickname")).isEqualTo("pebble");
        assertThat(((Map<?, ?>) data.get("member")).get("profileImageUrl")).isNull();
    }

    @Test
    void refusesUnknownOrExpiredStateEvenWhenCookieMatches() throws Exception {
        mockMvc.perform(post("/api/v1/auth/naver/login")
                        .cookie(new Cookie(NaverAuthController.STATE_COOKIE, "expired-state"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("code", "expired-state")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_OAUTH_STATE"));
        verify(naverOAuthGateway, never()).authenticate(anyString(), anyString());
    }

    @Test
    void invalidBearerTokenUsesStandardApiErrorResponse() throws Exception {
        mockMvc.perform(post("/api/v1/auth/naver/authorization")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty());
    }

    @Test
    void allowsCredentialRequestsOnlyFromConfiguredFrontendOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/auth/naver/login")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
        mockMvc.perform(options("/api/v1/auth/naver/login")
                        .header(HttpHeaders.ORIGIN, "https://untrusted.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void rejectsStateThatDoesNotMatchBrowserCookieBeforeCallingNaver() throws Exception {
        when(naverOAuthGateway.authorizationUrl(anyString()))
                .thenAnswer(invocation -> "https://nid.naver.com/oauth2.0/authorize?state=" + invocation.getArgument(0));
        Cookie stateCookie = beginAuthorizationAndGetCookie();
        stateCookie.setValue("different-state");

        mockMvc.perform(post("/api/v1/auth/naver/login")
                        .cookie(stateCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorizationCode\":\"code\",\"state\":\"returned-state\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_OAUTH_STATE"));

        verify(naverOAuthGateway, never()).authenticate(anyString(), anyString());
    }

    @Test
    void rejectsReusedStateBeforeCallingNaver() throws Exception {
        when(naverOAuthGateway.authorizationUrl(anyString()))
                .thenAnswer(invocation -> "https://nid.naver.com/oauth2.0/authorize?state=" + invocation.getArgument(0));
        Cookie stateCookie = beginAuthorizationAndGetCookie();
        String issuedState = stateCookie.getValue();
        stateCookie.setValue(issuedState);
        when(naverOAuthGateway.authenticate("code", issuedState))
                .thenReturn(new OAuthProfile("naver-id-reused", "pebble", null));

        login("code", issuedState, stateCookie);
        mockMvc.perform(post("/api/v1/auth/naver/login")
                        .cookie(stateCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("code", issuedState)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_OAUTH_STATE"));

        verify(naverOAuthGateway).authenticate("code", issuedState);
    }

    @Test
    void restoresWithdrawalPendingMemberOnLogin() throws Exception {
        Instant requestedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Member member = memberRepository.saveAndFlush(new Member(
                "pending", null, MemberStatus.WITHDRAWAL_PENDING,
                requestedAt, requestedAt.plusSeconds(60)));
        identityRepository.saveAndFlush(new MemberOAuthIdentity(member, OAuthProvider.NAVER, "pending-naver-id"));
        when(naverOAuthGateway.authorizationUrl(anyString()))
                .thenAnswer(invocation -> "https://nid.naver.com/oauth2.0/authorize?state=" + invocation.getArgument(0));
        Cookie stateCookie = beginAuthorizationAndGetCookie();
        String state = stateCookie.getValue();
        when(naverOAuthGateway.authenticate("code", state))
                .thenReturn(new OAuthProfile("pending-naver-id", "pending", null));
        mockMvc.perform(post("/api/v1/auth/naver/login")
                        .cookie(stateCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("code", state)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.member.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
        assertThat(memberRepository.findById(member.getId()).orElseThrow().getWithdrawalScheduledAt()).isNull();
    }

    @Test
    void mapsNaverCredentialFailureToInvalidCredentials() throws Exception {
        when(naverOAuthGateway.authorizationUrl(anyString()))
                .thenAnswer(invocation -> "https://nid.naver.com/oauth2.0/authorize?state=" + invocation.getArgument(0));
        Cookie stateCookie = beginAuthorizationAndGetCookie();
        String state = stateCookie.getValue();
        when(naverOAuthGateway.authenticate("bad-code", state))
                .thenThrow(new AuthException(AuthError.INVALID_CREDENTIALS));

        mockMvc.perform(post("/api/v1/auth/naver/login")
                        .cookie(stateCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("bad-code", state)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void refusesSuspendedMemberWithoutIssuingTokens() throws Exception {
        Member member = memberRepository.saveAndFlush(new Member("suspended", null, MemberStatus.SUSPENDED, null, null));
        identityRepository.saveAndFlush(new MemberOAuthIdentity(member, OAuthProvider.NAVER, "suspended-subject"));
        when(naverOAuthGateway.authorizationUrl(anyString()))
                .thenAnswer(invocation -> "https://nid.naver.com/oauth2.0/authorize?state=" + invocation.getArgument(0));
        Cookie cookie = beginAuthorizationAndGetCookie();
        when(naverOAuthGateway.authenticate("code", cookie.getValue()))
                .thenReturn(new OAuthProfile("suspended-subject", "suspended", null));
        MvcResult result = mockMvc.perform(post("/api/v1/auth/naver/login")
                        .cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("code", cookie.getValue())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_SUSPENDED"))
                .andReturn();
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    @Test
    void reportsNaverOutageAsProviderFailure() throws Exception {
        when(naverOAuthGateway.authorizationUrl(anyString()))
                .thenAnswer(invocation -> "https://nid.naver.com/oauth2.0/authorize?state=" + invocation.getArgument(0));
        Cookie cookie = beginAuthorizationAndGetCookie();
        when(naverOAuthGateway.authenticate("code", cookie.getValue()))
                .thenThrow(new AuthException(AuthError.OAUTH_PROVIDER_UNAVAILABLE));
        mockMvc.perform(post("/api/v1/auth/naver/login")
                        .cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("code", cookie.getValue())))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("OAUTH_PROVIDER_UNAVAILABLE"));
    }

    private Cookie beginAuthorizationAndGetCookie() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/naver/authorization"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authorizationUrl").exists())
                .andReturn();
        String setCookie = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith(NaverAuthController.STATE_COOKIE + "="))
                .findFirst()
                .orElseThrow();
        String value = setCookie.substring((NaverAuthController.STATE_COOKIE + "=").length(), setCookie.indexOf(';'));
        return new Cookie(NaverAuthController.STATE_COOKIE, value);
    }

    private MvcResult login(String code, String state, Cookie cookie) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/naver/login")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(code, state)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String loginBody(String code, String state) throws Exception {
        Map<String, String> body = new HashMap<>();
        body.put("authorizationCode", code);
        body.put("state", state);
        return objectMapper.writeValueAsString(body);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataFrom(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), Map.class).get("data") instanceof Map<?, ?> data
                ? (Map<String, Object>) data
                : Map.of();
    }
}
