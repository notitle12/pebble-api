package com.pebble.api.admin.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.admin.domain.AdminAccount;
import com.pebble.api.admin.domain.AdminRole;
import com.pebble.api.admin.infrastructure.persistence.AdminAccountRepository;
import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.auth.application.AdminRefreshTokenService;
import com.pebble.api.auth.application.AdminSessionService;
import com.pebble.api.auth.application.UserSessionService;
import com.pebble.api.auth.application.UserRefreshTokenService;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminMemberManagementIntegrationTest extends AuthenticationTestSupport {
    private static final String BASE = "/api/v1/admin/members";
    private static final String MASTER_PASSWORD = "Master-Integration-Secret-2026";
    private static final String MANAGER_PASSWORD = "Manager-Integration-Secret-2026";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AdminAccountRepository accounts;
    @Autowired MemberRepository members;
    @Autowired AdminSessionService sessions;
    @Autowired AdminRefreshTokenService refreshTokens;
    @Autowired AccessTokenService accessTokens;
    @Autowired UserSessionService userSessions;
    @Autowired UserRefreshTokenService userRefreshTokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired com.pebble.api.post.infrastructure.persistence.PostRepository posts;

    private AdminAccount master;
    private AdminAccount manager;
    private String masterAccess;
    private String managerAccess;
    private String userAccess;
    private final List<Long> sessionsToRevoke = new ArrayList<>();
    private final List<Long> memberSessionsToRevoke = new ArrayList<>();

    @BeforeEach
    void createActorsAndTokens() {
        master = saveAdmin("member-master-" + suffix(), MASTER_PASSWORD, AdminRole.MASTER);
        manager = saveAdmin("member-manager-" + suffix(), MANAGER_PASSWORD, AdminRole.MANAGER);
        masterAccess = login(master);
        managerAccess = login(manager);
        userAccess = accessTokens.issueForMember(734_811L, Instant.now());
    }

    @AfterEach
    void revokeTestSessions() {
        for (Long id : sessionsToRevoke) refreshTokens.revokeAll(id);
        for (Long id : memberSessionsToRevoke) userRefreshTokens.revokeAll(id);
    }

    @Test
    void managerAndMasterCanListAndReadOnlySafeMemberFieldsAreReturned() throws Exception {
        Member member = createMember("Target-" + suffix());
        for (String token : List.of(masterAccess, managerAccess)) {
            var response = mvc.perform(get(BASE + "/" + member.getId()).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(member.getId().toString()))
                    .andExpect(jsonPath("$.data.nickname").value(member.getNickname()))
                    .andExpect(jsonPath("$.data.profileCompleted").value(false)).andReturn();
            String json = response.getResponse().getContentAsString();
            assertThat(json).doesNotContain("oauth", "subject", "password", "token", "provider");
            mvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isArray());
        }
    }

    @Test
    void filtersSearchesLiterallyAndPaginatesWithSupportedSorts() throws Exception {
        String marker = "literal%_\\" + suffix();
        Member exact = createMember(marker);
        Member unrelated = createMember("other-" + suffix());
        var matching = mvc.perform(get(BASE).queryParam("q", "  " + marker + "  ")
                        .header(HttpHeaders.AUTHORIZATION, bearer(masterAccess)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1)).andReturn();
        assertThat(data(matching).path("content").get(0).path("id").asText()).isEqualTo(exact.getId().toString());
        mvc.perform(get(BASE).queryParam("q", marker.toUpperCase(java.util.Locale.ROOT))
                        .header(HttpHeaders.AUTHORIZATION, bearer(managerAccess)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get(BASE).queryParam("status", "ACTIVE").queryParam("sort", "nickname,asc")
                        .queryParam("page", "0").queryParam("size", "1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(managerAccess)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.size").value(1));
        mvc.perform(get(BASE).queryParam("q", " ").header(HttpHeaders.AUTHORIZATION, bearer(masterAccess)))
                .andExpect(status().isBadRequest());
        assertThat(unrelated.getId()).isNotEqualTo(exact.getId());
    }

    @Test
    void validatesQueryBodiesIdsAndPatchJsonStrictly() throws Exception {
        Member member = createMember("Strict-" + suffix());
        for (String query : List.of("?unknown=1", "?page=0&page=1", "?status=INACTIVE", "?sort=handle,up",
                "?size=101", "?page=2147483647&size=100", "?sort=createdAt", "?status=ACTIVE&status=SUSPENDED")) {
            mvc.perform(get(BASE + query).header(HttpHeaders.AUTHORIZATION, bearer(masterAccess)))
                    .andExpect(status().isBadRequest());
        }
        for (String q : List.of("\0", "\uD800", "x".repeat(201), " ")) {
            mvc.perform(get(BASE).queryParam("q", q).header(HttpHeaders.AUTHORIZATION, bearer(masterAccess)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get(BASE).content("unexpected").header(HttpHeaders.AUTHORIZATION, bearer(masterAccess)))
                .andExpect(status().isBadRequest());
        for (String id : List.of("0", "0" + member.getId(), "9223372036854775808")) {
            mvc.perform(get(BASE + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(masterAccess)))
                    .andExpect(status().isBadRequest());
        }
        for (String id : List.of("abc", "-1")) {
            mvc.perform(get(BASE + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(masterAccess)))
                    .andExpect(status().isForbidden());
        }
        for (String body : List.of("", "null", "{}", "{\"status\":null}", "{\"status\":\"WITHDRAWAL_PENDING\"}",
                "{\"status\":\"ACTIVE\",\"extra\":true}", "{\"status\":\"ACTIVE\",\"status\":\"SUSPENDED\"}",
                "{\"status\":\"ACTIVE\"} []")) {
            mvc.perform(statusChange(member.getId(), body, masterAccess)).andExpect(status().isBadRequest());
        }
        mvc.perform(patch(BASE + "/" + member.getId() + "/status?unexpected=x").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\"}").header(HttpHeaders.AUTHORIZATION, bearer(masterAccess)))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/" + member.getId() + "?q=x").header(HttpHeaders.AUTHORIZATION, bearer(masterAccess)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changesStatusAndWithdrawalPendingTargetConflicts() throws Exception {
        Member member = createMember("Change-" + suffix());
        mvc.perform(statusChange(member.getId(), "{\"status\":\"SUSPENDED\"}", managerAccess))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUSPENDED"));
        mvc.perform(statusChange(member.getId(), "{\"status\":\"ACTIVE\"}", masterAccess))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACTIVE"));
        Member pending = members.saveAndFlush(new Member("Pending-" + suffix(), null, MemberStatus.WITHDRAWAL_PENDING,
                Instant.now(), Instant.now().plusSeconds(86400)));
        mvc.perform(statusChange(pending.getId(), "{\"status\":\"ACTIVE\"}", masterAccess))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("WITHDRAWAL_PENDING"));
        mvc.perform(statusChange(pending.getId(), "{\"status\":\"SUSPENDED\"}", managerAccess))
                .andExpect(status().isConflict());
        mvc.perform(get(BASE).queryParam("status", "WITHDRAWAL_PENDING").queryParam("q", pending.getNickname())
                        .header(HttpHeaders.AUTHORIZATION, bearer(managerAccess)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].withdrawalRequestedAt").isNotEmpty());
        mvc.perform(get(BASE + "/" + Long.MAX_VALUE).header(HttpHeaders.AUTHORIZATION, bearer(managerAccess)))
                .andExpect(status().isNotFound());
        mvc.perform(statusChange(Long.MAX_VALUE, "{\"status\":\"ACTIVE\"}", managerAccess))
                .andExpect(status().isNotFound());
    }

    @Test
    void searchCoversBlogNameAndHandleAndStablePageOrderIncludesTieBreaker() throws Exception {
        String marker = "query-" + suffix();
        Member first = createMember(marker + "-one");
        Member second = createMember(marker + "-two");
        first.completeProfile("Blog-" + suffix(), "h" + suffix(), first.getNickname(), Instant.now());
        members.flush();
        for (String q : List.of(first.getBlogName(), first.getHandle())) {
            mvc.perform(get(BASE).queryParam("q", q.toUpperCase(java.util.Locale.ROOT))
                            .header(HttpHeaders.AUTHORIZATION, bearer(masterAccess)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content[0].id").value(first.getId().toString()));
        }
        var tied = Instant.parse("2026-01-01T00:00:00Z");
        jdbc.update("update member set created_at=? where id in (?,?)", java.sql.Timestamp.from(tied), first.getId(), second.getId());
        for (String sort : List.of("createdAt,desc", "createdAt,asc")) {
            boolean desc = sort.endsWith("desc");
            long expected = desc ? Math.max(first.getId(), second.getId()) : Math.min(first.getId(), second.getId());
            var page = mvc.perform(get(BASE).queryParam("q", marker).queryParam("sort", sort)
                            .queryParam("size", "1").header(HttpHeaders.AUTHORIZATION, bearer(managerAccess)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
                    .andExpect(jsonPath("$.data.totalPages").value(2)).andExpect(jsonPath("$.data.hasNext").value(true))
                    .andExpect(jsonPath("$.data.content[0].id").value(Long.toString(expected))).andReturn();
            var next = mvc.perform(get(BASE).queryParam("q", marker).queryParam("sort", sort).queryParam("page", "1")
                            .queryParam("size", "1").header(HttpHeaders.AUTHORIZATION, bearer(managerAccess)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.hasNext").value(false)).andReturn();
            assertThat(data(next).path("content").get(0).path("id").asText())
                    .isNotEqualTo(data(page).path("content").get(0).path("id").asText());
        }
    }

    @Test
    void suspensionPreservesPublicContentButRejectsMemberWrites() throws Exception {
        Member member = createMember("Content-" + suffix());
        member.completeProfile("Content-blog-" + suffix(), "h" + suffix(), member.getNickname(), Instant.now());
        members.flush();
        var publicPost = posts.saveAndFlush(new com.pebble.api.post.domain.Post(member, null, "Public content", null,
                com.pebble.api.post.domain.PostVisibility.PUBLIC, "public-content", 1));
        var original = userSessions.login(member.getId());
        mvc.perform(statusChange(member.getId(), "{\"status\":\"SUSPENDED\"}", managerAccess)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/posts/" + publicPost.getId())).andExpect(status().isOk());
        mvc.perform(patch("/api/v1/posts/" + publicPost.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Changed\"}").header(HttpHeaders.AUTHORIZATION, bearer(original.accessToken())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCOUNT_SUSPENDED"));
        assertThat(publicPost.getTitle()).isEqualTo("Public content");
    }

    @Test
    void suspensionBlocksMemberAndReactivationNeverRestoresOldRefreshFamilies() throws Exception {
        Member member = createMember("Session-" + suffix());
        var original = userSessions.login(member.getId());
        mvc.perform(statusChange(member.getId(), "{\"status\":\"SUSPENDED\"}", managerAccess))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, bearer(original.accessToken())))
                .andExpect(status().isForbidden());

        mvc.perform(statusChange(member.getId(), "{\"status\":\"ACTIVE\"}", masterAccess))
                .andExpect(status().isOk());
        mvc.perform(get(BASE + "/" + member.getId()).header(HttpHeaders.AUTHORIZATION, bearer(masterAccess)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.nickname").value(member.getNickname()))
                .andExpect(jsonPath("$.data.profileCompleted").value(false))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        mvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, bearer(original.accessToken())))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/token/refresh")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", original.refreshToken())))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
        var fresh = userSessions.login(member.getId());
        mvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, bearer(fresh.accessToken())))
                .andExpect(status().isOk());

        var beforeSameStatus = jdbc.queryForObject("select updated_at from member where id=?",
                java.sql.Timestamp.class, member.getId());
        mvc.perform(statusChange(member.getId(), "{\"status\":\"ACTIVE\"}", managerAccess)).andExpect(status().isOk());
        var afterSameStatus = jdbc.queryForObject("select updated_at from member where id=?",
                java.sql.Timestamp.class, member.getId());
        assertThat(afterSameStatus).isEqualTo(beforeSameStatus);
        mvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, bearer(fresh.accessToken())))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/token/refresh").header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", fresh.refreshToken())))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void corsAllowsConfiguredOriginsAndOnlyMemberRouteMethods() throws Exception {
        String origin = "http://localhost:3000";
        mvc.perform(options(BASE).header(HttpHeaders.ORIGIN, origin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin));
        mvc.perform(options(BASE + "/123/status").header(HttpHeaders.ORIGIN, origin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                .andExpect(status().isOk());
        mvc.perform(options(BASE).header(HttpHeaders.ORIGIN, "https://foreign.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
        mvc.perform(options(BASE).header(HttpHeaders.ORIGIN, origin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH"))
                .andExpect(status().isForbidden());
    }

    @Test
    void guestsMembersAndCookieOnlyRequestsCannotUseAdminEndpoints() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE).cookie(new jakarta.servlet.http.Cookie("admin_refresh_token", "not-access")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(userAccess))).andExpect(status().isForbidden());
    }

    private AdminAccount saveAdmin(String id, String password, AdminRole role) {
        return accounts.saveAndFlush(new AdminAccount(id, passwords.encode(password), role));
    }

    private String login(AdminAccount account) {
        sessionsToRevoke.add(account.getId());
        String password = account == master ? MASTER_PASSWORD : MANAGER_PASSWORD;
        return sessions.login(account.getLoginId(), password).tokens().accessToken();
    }

    private Member createMember(String nickname) {
        Member member = members.saveAndFlush(new Member(nickname, null, MemberStatus.ACTIVE, null, null));
        memberSessionsToRevoke.add(member.getId());
        return member;
    }

    private MockHttpServletRequestBuilder statusChange(long id, String body, String token) {
        return patch(BASE + "/" + id + "/status").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HttpHeaders.AUTHORIZATION, bearer(token));
    }

    private String bearer(String token) { return "Bearer " + token; }
    private String suffix() { return UUID.randomUUID().toString().replace("-", "").substring(0, 12); }
    private JsonNode data(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString()).path("data");
    }
}
