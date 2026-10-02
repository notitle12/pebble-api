package com.pebble.api.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.admin.domain.AdminAccount;
import com.pebble.api.admin.domain.AdminRole;
import com.pebble.api.admin.infrastructure.persistence.AdminAccountRepository;
import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.auth.application.AdminRefreshTokenService;
import com.pebble.api.auth.application.UserRefreshTokenService;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import com.pebble.api.support.AuthenticationTestSupport;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminSessionIntegrationTest extends AuthenticationTestSupport {
    private static final String ORIGIN = "http://localhost:3000";
    private static final String PASSWORD = "TestAdmin-Secret-123";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AdminAccountRepository admins;
    @Autowired EntityManager entityManager;
    @Autowired PasswordEncoder passwordEncoder;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean AdminRefreshTokenService adminRefreshTokens;
    @Autowired UserRefreshTokenService userRefreshTokens;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessTokenService accessTokens;
    @Autowired MemberRepository members;

    private final AtomicInteger requestIp = new AtomicInteger(20);
    private AdminAccount account;
    private String issuedUserToken;

    @BeforeEach
    void createManager() {
        account = admins.saveAndFlush(new AdminAccount(loginId(), passwordEncoder.encode(PASSWORD), AdminRole.MANAGER));
    }

    @AfterEach
    void removeAdminSessions() {
        if (account != null) adminRefreshTokens.revokeAll(account.getId());
        if (issuedUserToken != null) userRefreshTokens.logout(issuedUserToken);
    }

    @Test
    void loginCreatesOnlineAdminSessionAndCookieWithAdminScope() throws Exception {
        var result = mvc.perform(login(account.getLoginId(), PASSWORD, ORIGIN))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.admin.role").value("MANAGER"))
                        .andExpect(jsonPath("$.data.admin.status").value("ACTIVE"))
                        .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                        .andReturn();

        String access = objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
        String cookie = cookieValue(result.getResponse().getHeader(HttpHeaders.SET_COOKIE));
        String[] claims = access.split("\\.");
        var payload = objectMapper.readTree(java.util.Base64.getUrlDecoder().decode(claims[1]));
        assertThat(payload.path("sub").asText()).isEqualTo("admin:" + account.getId());
        assertThat(payload.path("role").asText()).isEqualTo("MANAGER");
        assertThat(payload.path("sid").asText()).isNotBlank();
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("admin_refresh_token=").contains("Path=/api/v1/admin/auth").contains("Max-Age=3600");
        assertThat(setCookie).contains("HttpOnly").contains("Secure").contains("SameSite=Lax");

        mvc.perform(get("/api/v1/categories").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isOk());
        assertThat(cookie).hasSize(43);
        assertThat(redis.keys("pebble:auth:admin:refresh-token:*")).allSatisfy(key -> assertThat(key).doesNotContain(cookie));
    }

    @Test
    void unknownWrongAndInactiveCredentialsShareOneFailure() throws Exception {
        mvc.perform(login("missing-admin", PASSWORD, ORIGIN)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
        mvc.perform(login(account.getLoginId(), "wrong-password", ORIGIN)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
        jdbc.update("update admin_account set status='INACTIVE' where id=?", account.getId());
        entityManager.clear();
        mvc.perform(login(account.getLoginId(), PASSWORD, ORIGIN)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void rejectsInvalidShapeDuplicateFieldsAndOriginAndAllowsExactCorsOrigin() throws Exception {
        mvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}")
                .header(HttpHeaders.ORIGIN, ORIGIN).with(remoteIp()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + account.getLoginId() + "\",\"password\":null}")
                .header(HttpHeaders.ORIGIN, ORIGIN).with(remoteIp()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + account.getLoginId() + "\",\"loginId\":\"" + account.getLoginId()
                        + "\",\"password\":\"" + PASSWORD + "\"}")
                .header(HttpHeaders.ORIGIN, ORIGIN).with(remoteIp()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + account.getLoginId() + "\",\"password\":\"" + PASSWORD + "\",\"role\":\"MASTER\"}")
                .header(HttpHeaders.ORIGIN, ORIGIN).with(remoteIp()))
                .andExpect(status().isBadRequest());
        mvc.perform(login(account.getLoginId(), "x".repeat(129), ORIGIN)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/auth/login?unexpected=1").contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(account.getLoginId(), PASSWORD)).header(HttpHeaders.ORIGIN, ORIGIN).with(remoteIp()))
                .andExpect(status().isBadRequest());
        mvc.perform(login(account.getLoginId(), PASSWORD, "https://attacker.example")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(account.getLoginId(), PASSWORD)).with(remoteIp()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(account.getLoginId(), PASSWORD)).header(HttpHeaders.ORIGIN, ORIGIN, ORIGIN).with(remoteIp()))
                .andExpect(status().isForbidden());
        mvc.perform(options("/api/v1/admin/auth/login").header(HttpHeaders.ORIGIN, ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN));
        mvc.perform(options("/api/v1/admin/auth/login").header(HttpHeaders.ORIGIN, ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/auth/token/refresh").contentType(MediaType.APPLICATION_JSON).content("{}")
                .header(HttpHeaders.ORIGIN, ORIGIN).with(remoteIp())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/auth/token/refresh?unexpected=1").header(HttpHeaders.ORIGIN, ORIGIN)
                .with(remoteIp())).andExpect(status().isBadRequest());
    }

    @Test
    void logoutAndRefreshReuseInvalidateTheOriginalAccessToken() throws Exception {
        var login = loginResult();
        String access = login.accessToken();
        mvc.perform(post("/api/v1/admin/auth/logout").cookie(login.adminCookie()).header(HttpHeaders.ORIGIN, ORIGIN)
                .with(remoteIp())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/categories").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
        mvc.perform(post("/api/v1/admin/auth/token/refresh").cookie(login.adminCookie()).header(HttpHeaders.ORIGIN, ORIGIN)
                .with(remoteIp())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void refreshTokenReuseRevokesTheSessionAndItsExistingAccessToken() throws Exception {
        Login login = loginResult();
        mvc.perform(post("/api/v1/admin/auth/token/refresh").cookie(login.adminCookie()).header(HttpHeaders.ORIGIN, ORIGIN)
                .with(remoteIp())).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/auth/token/refresh").cookie(login.adminCookie()).header(HttpHeaders.ORIGIN, ORIGIN)
                .with(remoteIp())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
        mvc.perform(get("/api/v1/categories").header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
    }

    @Test
    void adminAndUserRefreshCookiesCannotCrossRefreshEndpoints() throws Exception {
        Login login = loginResult();
        var userCookie = userRefreshTokens.issue(987654321L, Instant.now());
        issuedUserToken = userCookie.value();
        mvc.perform(post("/api/v1/admin/auth/token/refresh").cookie(login.adminCookie("refresh_token"))
                .header(HttpHeaders.ORIGIN, ORIGIN).with(remoteIp())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/token/refresh").cookie(login.adminCookie()).header(HttpHeaders.ORIGIN, ORIGIN).with(remoteIp()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/auth/token/refresh").cookie(new jakarta.servlet.http.Cookie("admin_refresh_token", userCookie.value()))
                .header(HttpHeaders.ORIGIN, ORIGIN).with(remoteIp())).andExpect(status().isUnauthorized());
    }

    @Test
    void masterRoleIsIssuedOnlyFromTheStoredAccountAndIncludesSessionIdentity() throws Exception {
        jdbc.update("update admin_account set role='MASTER' where id=?", account.getId());
        entityManager.clear();
        var result = mvc.perform(login(account.getLoginId(), PASSWORD, ORIGIN)).andExpect(status().isOk()).andReturn();
        var body = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        var claims = objectMapper.readTree(java.util.Base64.getUrlDecoder().decode(body.path("accessToken").asText().split("\\.")[1]));
        assertThat(body.path("admin").path("role").asText()).isEqualTo("MASTER");
        assertThat(claims.path("sub").asText()).isEqualTo("admin:" + account.getId());
        assertThat(claims.path("role").asText()).isEqualTo("MASTER");
        assertThat(claims.path("sid").asText()).isNotBlank();
    }

    @Test
    void adminCannotUseUserResourcesAndExistingUserBearerWritesRemainAvailable() throws Exception {
        String adminAccess = loginResult().accessToken();
        mvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminAccess))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("INSUFFICIENT_ROLE"));

        Member member = members.saveAndFlush(new Member("csrf-test", null, MemberStatus.ACTIVE, null, null));
        String userAccess = accessTokens.issueForMember(member.getId(), Instant.now());
        mvc.perform(post("/api/v1/members/me/profile").contentType(MediaType.APPLICATION_JSON)
                .content("{\"blogName\":\"csrf test\",\"handle\":\"csrf-test\"}").header(HttpHeaders.AUTHORIZATION, "Bearer " + userAccess))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/members/me/profile").contentType(MediaType.APPLICATION_JSON)
                .content("{\"blogName\":\"guest test\",\"handle\":\"guest-test\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void accountStatusAndRoleChangesInvalidateAccessAndRefreshAndCannotReactivateSession() throws Exception {
        Login statusLogin = loginResult();
        jdbc.update("update admin_account set status='INACTIVE' where id=?", account.getId());
        entityManager.clear();
        mvc.perform(get("/api/v1/categories").header(HttpHeaders.AUTHORIZATION, "Bearer " + statusLogin.accessToken()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
        mvc.perform(post("/api/v1/admin/auth/token/refresh").cookie(statusLogin.adminCookie()).header(HttpHeaders.ORIGIN, ORIGIN)
                .with(remoteIp())).andExpect(status().isUnauthorized());

        jdbc.update("update admin_account set status='ACTIVE', role='MASTER' where id=?", account.getId());
        entityManager.clear();
        mvc.perform(get("/api/v1/categories").header(HttpHeaders.AUTHORIZATION, "Bearer " + statusLogin.accessToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changingRoleInvalidatesPreviouslyIssuedAccessAndRefreshTokens() throws Exception {
        Login login = loginResult();
        jdbc.update("update admin_account set role='MASTER' where id=?", account.getId());
        entityManager.clear();
        mvc.perform(get("/api/v1/categories").header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
        mvc.perform(post("/api/v1/admin/auth/token/refresh").cookie(login.adminCookie()).header(HttpHeaders.ORIGIN, ORIGIN)
                .with(remoteIp())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void limitsRepeatedCredentialChecksByAccount() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(login(account.getLoginId(), "wrong-password", ORIGIN)).andExpect(status().isUnauthorized());
        }
        mvc.perform(login(account.getLoginId(), "wrong-password", ORIGIN)).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    @Test
    void redisOutageRejectsAdminAccessAndRefreshWhileUserAccessIsIndependent() throws Exception {
        Login login = loginResult();
        try {
            org.mockito.Mockito.doThrow(new org.springframework.data.redis.RedisConnectionFailureException("test Redis offline"))
                    .when(adminRefreshTokens).isSessionActive(org.mockito.ArgumentMatchers.anyLong(),
                            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
            mvc.perform(get("/api/v1/categories").header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken()))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
            String userAccess = accessTokens.issueForMember(123L, Instant.now());
            mvc.perform(get("/api/v1/categories").header(HttpHeaders.AUTHORIZATION, "Bearer " + userAccess)).andExpect(status().isOk());
            org.mockito.Mockito.doThrow(new org.springframework.data.redis.RedisConnectionFailureException("test Redis offline"))
                    .when(adminRefreshTokens).find(login.adminCookie().getValue());
            mvc.perform(post("/api/v1/admin/auth/token/refresh").cookie(login.adminCookie())
                    .header(HttpHeaders.ORIGIN, ORIGIN).with(remoteIp()))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
        } finally { org.mockito.Mockito.reset(adminRefreshTokens); }
    }

    private Login loginResult() throws Exception {
        var result = mvc.perform(login(account.getLoginId(), PASSWORD, ORIGIN)).andExpect(status().isOk()).andReturn();
        String access = objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
        return new Login(access, new jakarta.servlet.http.Cookie("admin_refresh_token", cookieValue(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(String id, String password, String origin) {
        return post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(id, password)).header(HttpHeaders.ORIGIN, origin).with(remoteIp());
    }

    private String loginBody(String id, String password) {
        try { return objectMapper.writeValueAsString(java.util.Map.of("loginId", id, "password", password)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private String loginId() { return "admin-" + UUID.randomUUID().toString().substring(0, 8); }

    private static String cookieValue(String setCookie) {
        return setCookie.substring("admin_refresh_token=".length(), setCookie.indexOf(';'));
    }

    private RequestPostProcessor remoteIp() {
        int host = requestIp.getAndIncrement();
        return request -> { request.setRemoteAddr("127.0.0." + host); return request; };
    }

    private record Login(String accessToken, jakarta.servlet.http.Cookie adminCookie) {
        private jakarta.servlet.http.Cookie adminCookie(String name) {
            return new jakarta.servlet.http.Cookie(name, adminCookie.getValue());
        }
    }
}
