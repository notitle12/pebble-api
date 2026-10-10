package com.pebble.api.member.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.auth.application.UserSessionService;
import com.pebble.api.auth.application.OAuthLoginService;
import com.pebble.api.auth.application.UserRefreshTokenService;
import com.pebble.api.auth.application.oauth.OAuthProviderClient;
import com.pebble.api.member.application.MemberProfileService;
import com.pebble.api.member.application.MemberWithdrawalService;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberOAuthIdentity;
import com.pebble.api.member.domain.OAuthProvider;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberOAuthIdentityRepository;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import jakarta.servlet.http.Cookie;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MemberWithdrawalIntegrationTest extends AuthenticationTestSupport {
    private static final String PATH = "/api/v1/members/me";

    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired AccessTokenService tokens;
    @Autowired UserSessionService sessions;
    @Autowired OAuthLoginService naverLogin;
    @Autowired MemberOAuthIdentityRepository identities;
    @Autowired MemberProfileService profiles;
    @Autowired MemberWithdrawalService withdrawals;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired UserRefreshTokenService refreshTokens;
    @MockitoBean(name = "naverOAuthClient") OAuthProviderClient naver;

    private Member member;
    private UserSessionService.LoginGrant login;

    @BeforeEach
    void setUp() {
        member = members.saveAndFlush(new Member("member-" + UUID.randomUUID().toString().substring(0, 12),
                null, MemberStatus.ACTIVE, null, null));
        login = sessions.login(member.getId());
    }

    @AfterEach
    void clearRedisSessions() {
        refreshTokens.revokeAll(member.getId());
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            jdbc.update("delete from member_oauth_identity where member_id=?", member.getId());
            members.deleteById(member.getId());
        }
    }

    @Test
    void requestSchedulesWithdrawalAndExpiresRefreshCookie() throws Exception {
        Instant before = Instant.now();
        var result = completeWithdrawal();
        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).anySatisfy(cookie ->
                assertThat(cookie).contains("refresh_token=", "Max-Age=0", "HttpOnly", "Secure", "SameSite=Lax"));
        Member pending = members.findById(member.getId()).orElseThrow();
        assertThat(pending.getWithdrawalScheduledAt()).isBetween(before.plus(Duration.ofDays(6)), before.plus(Duration.ofDays(8)));
        mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/token/refresh").header(HttpHeaders.ORIGIN, "http://localhost:3000").cookie(new Cookie("refresh_token", login.refreshToken())))
                .andExpect(status().isUnauthorized());

        mvc.perform(delete(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken()))
                .andExpect(status().isForbidden());
        mvc.perform(delete(PATH)).andExpect(status().isForbidden());
        mvc.perform(delete(PATH).cookie(new Cookie("refresh_token", login.refreshToken())))
                .andExpect(status().isForbidden());
        mvc.perform(post(PATH + "/profile").header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"blogName\":\"blocked\",\"handle\":\"blocked-user\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch(PATH + "/profile").header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"blocked\"}"))
                .andExpect(status().isForbidden());
        assertThat(members.findById(member.getId()).orElseThrow().getWithdrawalScheduledAt())
                .isEqualTo(pending.getWithdrawalScheduledAt());
    }

    @Test
    void rejectsDeleteQueryAndBody() throws Exception {
        mvc.perform(delete(PATH + "?confirm=true").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest());
        mvc.perform(delete(PATH).header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancellationRestoresAccountWithoutRestoringOldSession() throws Exception {
        String subject = "withdrawal-subject-" + UUID.randomUUID();
        identities.saveAndFlush(new MemberOAuthIdentity(member, OAuthProvider.NAVER, subject));
        var oldLogin = login;
        withdrawalsRequest();

        var authorization = naverLogin.beginAuthorization(OAuthProvider.NAVER);
        when(naver.authenticate("valid-code", authorization.state()))
                .thenReturn(new OAuthProviderClient.OAuthProfile(subject, member.getNickname(), null));
        var cancellation = mvc.perform(post("/api/v1/auth/naver/withdrawal/cancel")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .cookie(new Cookie("naver_oauth_state", authorization.state()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorizationCode\":\"valid-code\",\"state\":\"" + authorization.state() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists(HttpHeaders.SET_COOKIE));
        List<String> cookies = cancellation.andReturn().getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(cookies).hasSize(2);
        assertThat(cookies).anySatisfy(value -> assertThat(value).contains("Path=/api/v1/auth/naver;", "Max-Age=0", "HttpOnly", "Secure", "SameSite=Lax"));
        assertThat(cookies).anySatisfy(value -> assertThat(value).contains("Path=/api/v1/auth;", "Max-Age=0", "HttpOnly", "Secure", "SameSite=Lax"));
        Member restored = members.findById(member.getId()).orElseThrow();
        assertThat(restored.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(restored.getWithdrawalRequestedAt()).isNull();
        assertThat(restored.getWithdrawalScheduledAt()).isNull();
        mvc.perform(post("/api/v1/auth/token/refresh").header(HttpHeaders.ORIGIN, "http://localhost:3000").cookie(new Cookie("refresh_token", oldLogin.refreshToken())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + oldLogin.accessToken()))
                .andExpect(status().isOk());
    }

    @Test
    void cancellationRejectsInvalidBodiesBeforeConsumingState() throws Exception {
        String subject = "withdrawal-subject-" + UUID.randomUUID();
        identities.saveAndFlush(new MemberOAuthIdentity(member, OAuthProvider.NAVER, subject));
        withdrawalsRequest();
        var authorization = naverLogin.beginAuthorization(OAuthProvider.NAVER);
        when(naver.authenticate("valid-code", authorization.state()))
                .thenReturn(new OAuthProviderClient.OAuthProfile(subject, member.getNickname(), null));
        String path = "/api/v1/auth/naver/withdrawal/cancel";
        for (String invalid : java.util.List.of(
                "{}", "null", "[]", "{\"authorizationCode\":null,\"state\":\"s\"}",
                "{\"authorizationCode\":\" \",\"state\":\"s\"}",
                "{\"authorizationCode\":\"x\",\"state\":null}",
                "{\"authorizationCode\":\"x\",\"state\":\"s\",\"extra\":1}",
                "{\"authorizationCode\":\"" + "x".repeat(4097) + "\",\"state\":\"s\"}",
                "{\"authorizationCode\":\"x\",\"state\":\"" + "s".repeat(257) + "\"}",
                "{\"authorizationCode\":\"x\",\"authorizationCode\":\"y\",\"state\":\"s\"}",
                "{\"authorizationCode\":\"x\",\"state\":\"s\"} {}",
                "{\"authorizationCode\":\"\\u0000\",\"state\":\"s\"}",
                "{\"authorizationCode\":\"\\uD800\",\"state\":\"s\"}")) {
            mvc.perform(post(path).header(HttpHeaders.ORIGIN, "http://localhost:3000")
                            .cookie(new Cookie("naver_oauth_state", authorization.state()))
                            .contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post(path + "?extra=1").header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .cookie(new Cookie("naver_oauth_state", authorization.state()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorizationCode\":\"valid-code\",\"state\":\"" + authorization.state() + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path).header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .cookie(new Cookie("naver_oauth_state", "mismatch"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorizationCode\":\"valid-code\",\"state\":\"" + authorization.state() + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .cookie(new Cookie("naver_oauth_state", authorization.state()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorizationCode\":\"valid-code\",\"state\":\"" + authorization.state() + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void cancellationEnforcesOriginIdentityStatusExpiryAndSingleUseState() throws Exception {
        String subject = "withdrawal-subject-" + UUID.randomUUID();
        identities.saveAndFlush(new MemberOAuthIdentity(member, OAuthProvider.NAVER, subject));
        withdrawalsRequest();
        String path = "/api/v1/auth/naver/withdrawal/cancel";
        var authorization = naverLogin.beginAuthorization(OAuthProvider.NAVER);
        when(naver.authenticate("valid-code", authorization.state()))
                .thenReturn(new OAuthProviderClient.OAuthProfile(subject, member.getNickname(), null));
        String body = "{\"authorizationCode\":\"valid-code\",\"state\":\"" + authorization.state() + "\"}";
        Cookie stateCookie = new Cookie("naver_oauth_state", authorization.state());
        mvc.perform(post(path).header(HttpHeaders.ORIGIN, "https://untrusted.example").cookie(stateCookie)
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).cookie(stateCookie).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).header(HttpHeaders.ORIGIN, "null").cookie(stateCookie)
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).header(HttpHeaders.ORIGIN, "http://localhost:3000", "https://untrusted.example")
                        .cookie(stateCookie).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header(HttpHeaders.ORIGIN, "http://localhost:3000").cookie(stateCookie)
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(post(path).header(HttpHeaders.ORIGIN, "http://localhost:3000").cookie(stateCookie)
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());

        var activeState = naverLogin.beginAuthorization(OAuthProvider.NAVER);
        when(naver.authenticate("valid-code", activeState.state()))
                .thenReturn(new OAuthProviderClient.OAuthProfile(subject, member.getNickname(), null));
        mvc.perform(post(path).header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .cookie(new Cookie("naver_oauth_state", activeState.state())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorizationCode\":\"valid-code\",\"state\":\"" + activeState.state() + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("WITHDRAWAL_NOT_PENDING"));

        jdbc.update("update member set status='SUSPENDED' where id=?", member.getId());
        emClear();
        var suspendedState = naverLogin.beginAuthorization(OAuthProvider.NAVER);
        when(naver.authenticate("valid-code", suspendedState.state()))
                .thenReturn(new OAuthProviderClient.OAuthProfile(subject, member.getNickname(), null));
        mvc.perform(post(path).header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .cookie(new Cookie("naver_oauth_state", suspendedState.state())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorizationCode\":\"valid-code\",\"state\":\"" + suspendedState.state() + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("WITHDRAWAL_NOT_PENDING"));
    }

    @Test
    void pendingNaverLoginRestoresAccountAndUnknownCancellationDoesNotRegister() throws Exception {
        String subject = "withdrawal-subject-" + UUID.randomUUID();
        identities.saveAndFlush(new MemberOAuthIdentity(member, OAuthProvider.NAVER, subject));
        withdrawalsRequest();
        var loginState = naverLogin.beginAuthorization(OAuthProvider.NAVER);
        when(naver.authenticate("login-code", loginState.state()))
                .thenReturn(new OAuthProviderClient.OAuthProfile(subject, member.getNickname(), null));
        mvc.perform(post("/api/v1/auth/naver/login").cookie(new Cookie("naver_oauth_state", loginState.state()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorizationCode\":\"login-code\",\"state\":\"" + loginState.state() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.member.status").value("ACTIVE"));

        var unknownState = naverLogin.beginAuthorization(OAuthProvider.NAVER);
        String unknownSubject = "unregistered-" + UUID.randomUUID();
        when(naver.authenticate("valid-code", unknownState.state()))
                .thenReturn(new OAuthProviderClient.OAuthProfile(unknownSubject, "unknown", null));
        long count = members.count();
        mvc.perform(post("/api/v1/auth/naver/withdrawal/cancel").header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .cookie(new Cookie("naver_oauth_state", unknownState.state())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorizationCode\":\"valid-code\",\"state\":\"" + unknownState.state() + "\"}"))
                .andExpect(status().isNotFound());
        assertThat(members.count()).isEqualTo(count);
    }

    @Test
    void expiredWithdrawalCannotBeCancelled() throws Exception {
        String subject = "withdrawal-subject-" + UUID.randomUUID();
        identities.saveAndFlush(new MemberOAuthIdentity(member, OAuthProvider.NAVER, subject));
        withdrawalsRequest();
        jdbc.update("update member set withdrawal_scheduled_at=current_timestamp - interval '1 second' where id=?", member.getId());
        em.clear();
        var state = naverLogin.beginAuthorization(OAuthProvider.NAVER);
        when(naver.authenticate("valid-code", state.state()))
                .thenReturn(new OAuthProviderClient.OAuthProfile(subject, member.getNickname(), null));
        mvc.perform(post("/api/v1/auth/naver/withdrawal/cancel").header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .cookie(new Cookie("naver_oauth_state", state.state())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorizationCode\":\"valid-code\",\"state\":\"" + state.state() + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("WITHDRAWAL_EXPIRED"));
    }

    @Test
    void publicPostAndItsCommentAndLikeDisappearWhilePendingAndReturnAfterCancellation() throws Exception {
        Member profiled = profiles.complete(member.getId(), "withdrawal-blog-" + member.getId(),
                "withdrawal-" + member.getId(), null);
        var createdProject = mvc.perform(post("/api/v1/projects").header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"withdrawal project\",\"lifecycleStatus\":\"IN_PROGRESS\",\"visibilityStatus\":\"PUBLIC\",\"tagIds\":[],\"features\":[],\"links\":[]}"))
                .andExpect(status().isCreated());
        String projectId = mapper.readTree(createdProject.andReturn().getResponse().getContentAsString()).at("/data/id").asText();
        var createdBoard = mvc.perform(post("/api/v1/boards").header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"withdrawal board\"}"))
                .andExpect(status().isCreated());
        String boardId = mapper.readTree(createdBoard.andReturn().getResponse().getContentAsString()).at("/data/id").asText();
        String postJson = "{\"title\":\"withdrawal post\",\"summary\":null,\"visibilityStatus\":\"PUBLIC\","
                + "\"blocks\":[{\"type\":\"TEXT\",\"content\":\"body\"}],\"tagIds\":[],\"boardId\":\"" + boardId + "\"}";
        var createdPost = mvc.perform(post("/api/v1/posts").header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(postJson)).andExpect(status().isCreated());
        String postId = mapper.readTree(createdPost.andReturn().getResponse().getContentAsString()).at("/data/id").asText();
        var createdComment = mvc.perform(post("/api/v1/posts/" + postId + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"comment body\",\"visibility\":\"PUBLIC\"}"))
                .andExpect(status().isCreated());
        String commentId = mapper.readTree(createdComment.andReturn().getResponse().getContentAsString()).at("/data/id").asText();
        mvc.perform(put("/api/v1/posts/" + postId + "/like").header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken()))
                .andExpect(status().isNoContent());
        String subject = "withdrawal-subject-" + UUID.randomUUID();
        identities.saveAndFlush(new MemberOAuthIdentity(profiled, OAuthProvider.NAVER, subject));
        withdrawalsRequest();
        mvc.perform(get("/api/v1/posts/" + postId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/projects/" + projectId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/members/" + member.getId() + "/projects"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/members/" + member.getId() + "/boards"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/posts/" + postId + "/comments")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/posts/" + postId + "/comments/" + commentId)).andExpect(status().isNotFound());

        var state = naverLogin.beginAuthorization(OAuthProvider.NAVER);
        when(naver.authenticate("valid-code", state.state()))
                .thenReturn(new OAuthProviderClient.OAuthProfile(subject, member.getNickname(), null));
        mvc.perform(post("/api/v1/auth/naver/withdrawal/cancel").header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .cookie(new Cookie("naver_oauth_state", state.state())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorizationCode\":\"valid-code\",\"state\":\"" + state.state() + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/posts/" + postId)).andExpect(status().isOk()).andExpect(jsonPath("$.data.likeCount").value(1));
        mvc.perform(get("/api/v1/projects/" + projectId)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/members/" + member.getId() + "/boards"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(boardId));
        mvc.perform(get("/api/v1/posts/" + postId + "/comments")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void withdrawalAndCancellationPreflightOnlyAllowRegisteredOriginAndMethod() throws Exception {
        mvc.perform(options(PATH).header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "DELETE")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"));
        mvc.perform(options(PATH).header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
        mvc.perform(options(PATH).header(HttpHeaders.ORIGIN, "https://untrusted.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "DELETE"))
                .andExpect(status().isForbidden());
        mvc.perform(options("/api/v1/auth/naver/withdrawal/cancel").header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void naverReauthenticationRunsBeforeOpeningCancellationTransaction() {
        String subject = "withdrawal-subject-" + UUID.randomUUID();
        identities.saveAndFlush(new MemberOAuthIdentity(member, OAuthProvider.NAVER, subject));
        withdrawalsRequest();
        var state = naverLogin.beginAuthorization(OAuthProvider.NAVER);
        when(naver.authenticate("valid-code", state.state())).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new OAuthProviderClient.OAuthProfile(subject, member.getNickname(), null);
        });
        naverLogin.cancelWithdrawal(OAuthProvider.NAVER, "valid-code", state.state(), state.state());
        assertThat(members.findById(member.getId()).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    void loginStateCannotBeUsedToWithdrawAndCookieOriginIsRequired() throws Exception {
        var state = naverLogin.beginAuthorization(OAuthProvider.NAVER).state();
        String body = mapper.writeValueAsString(java.util.Map.of("authorizationCode", "code", "state", state));
        mvc.perform(post("/api/v1/auth/naver/withdrawal").cookie(new Cookie("naver_oauth_state", state))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/naver/withdrawal")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000").cookie(new Cookie("naver_oauth_state", state))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        org.mockito.Mockito.verify(naver, org.mockito.Mockito.never()).authenticateAndRevoke(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        assertThat(members.findById(member.getId()).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    void providerFailureKeepsInternalAccountActive() throws Exception {
        String subject = "failure-" + UUID.randomUUID();
        identities.saveAndFlush(new MemberOAuthIdentity(member, OAuthProvider.NAVER, subject));
        var grant = naverLogin.beginWithdrawal(member.getId());
        org.mockito.Mockito.doThrow(new com.pebble.api.auth.domain.AuthException(
                com.pebble.api.auth.domain.AuthError.OAUTH_PROVIDER_UNAVAILABLE))
                .when(naver).authenticateAndRevoke("code", grant.state(), subject);
        mvc.perform(post("/api/v1/auth/naver/withdrawal")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000").cookie(new Cookie("naver_oauth_state", grant.state()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("authorizationCode", "code", "state", grant.state()))))
                .andExpect(status().isBadGateway());
        assertThat(members.findById(member.getId()).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(members.findById(member.getId()).orElseThrow().getWithdrawalScheduledAt()).isNull();
    }

    @Test
    void withdrawalStateCannotSignInAndExpiredReservationCannotBeRestored() throws Exception {
        String subject = "expired-" + UUID.randomUUID();
        identities.saveAndFlush(new MemberOAuthIdentity(member, OAuthProvider.NAVER, subject));
        var state = naverLogin.beginWithdrawal(member.getId());
        mvc.perform(post("/api/v1/auth/naver/login").cookie(new Cookie("naver_oauth_state", state.state()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("authorizationCode", "code", "state", state.state()))))
                .andExpect(status().isUnauthorized());
        jdbc.update("update member set status='WITHDRAWAL_PENDING', withdrawal_requested_at=now()-interval '8 days', withdrawal_scheduled_at=now()-interval '1 day' where id=?", member.getId());
        em.clear();
        var loginState = naverLogin.beginAuthorization(OAuthProvider.NAVER);
        when(naver.authenticate("code", loginState.state())).thenReturn(new OAuthProviderClient.OAuthProfile(subject, null, null));
        mvc.perform(post("/api/v1/auth/naver/login").cookie(new Cookie("naver_oauth_state", loginState.state()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("authorizationCode", "code", "state", loginState.state()))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("WITHDRAWAL_EXPIRED"));
        assertThat(members.findById(member.getId()).orElseThrow().getStatus()).isEqualTo(MemberStatus.WITHDRAWAL_PENDING);
    }

    private org.springframework.test.web.servlet.MvcResult completeWithdrawal() throws Exception {
        String subject = "unlink-" + UUID.randomUUID();
        identities.saveAndFlush(new MemberOAuthIdentity(member, OAuthProvider.NAVER, subject));
        when(naver.withdrawalAuthorizationUrl(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> "https://nid.naver.com/oauth2.0/authorize?state=" + call.getArgument(0));
        var started = mvc.perform(delete(PATH).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk()).andReturn();
        assertThat(members.findById(member.getId()).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
        String cookieHeader = started.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        String state = cookieHeader.substring("naver_oauth_state=".length(), cookieHeader.indexOf(';'));
        var result = mvc.perform(post("/api/v1/auth/naver/withdrawal")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .cookie(new Cookie("naver_oauth_state", state))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("authorizationCode", "unlink-code", "state", state))))
                .andExpect(status().isAccepted()).andReturn();
        org.mockito.Mockito.verify(naver).authenticateAndRevoke("unlink-code", state, subject);
        mvc.perform(post("/api/v1/auth/naver/withdrawal")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .cookie(new Cookie("naver_oauth_state", state)).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("authorizationCode", "unlink-code", "state", state))))
                .andExpect(status().isUnauthorized());
        return result;
    }

    private String bearer() {
        return "Bearer " + tokens.issueForMember(member.getId(), Instant.now());
    }

    private void withdrawalsRequest() {
        withdrawals.request(member.getId());
        em.clear();
    }

    private void emClear() {
        em.clear();
    }
}
