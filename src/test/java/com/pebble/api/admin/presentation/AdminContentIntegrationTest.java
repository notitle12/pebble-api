package com.pebble.api.admin.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.admin.application.AdminContentService;
import com.pebble.api.admin.domain.AdminAccount;
import com.pebble.api.admin.domain.AdminRole;
import com.pebble.api.admin.infrastructure.persistence.AdminAccountRepository;
import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.auth.application.AdminRefreshTokenService;
import com.pebble.api.auth.application.AdminSessionService;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.post.domain.BlockType;
import com.pebble.api.post.domain.Post;
import com.pebble.api.post.domain.PostBlock;
import com.pebble.api.post.domain.PostVisibility;
import com.pebble.api.post.infrastructure.persistence.PostBlockRepository;
import com.pebble.api.post.infrastructure.persistence.PostRepository;
import com.pebble.api.project.domain.Project;
import com.pebble.api.project.domain.ProjectLifecycleStatus;
import com.pebble.api.project.domain.ProjectVisibility;
import com.pebble.api.project.infrastructure.persistence.ProjectRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminContentIntegrationTest extends AuthenticationTestSupport {
    private static final String PASSWORD = "Content-Moderation-Test-2026";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AdminContentService management;
    @Autowired AdminAccountRepository accounts;
    @Autowired AdminSessionService sessions;
    @Autowired AdminRefreshTokenService refreshTokens;
    @Autowired PasswordEncoder passwords;
    @Autowired AccessTokenService accessTokens;
    @Autowired MemberRepository members;
    @Autowired PostRepository posts;
    @Autowired PostBlockRepository blocks;
    @Autowired ProjectRepository projects;
    @Autowired EntityManager entities;
    @Autowired JdbcTemplate jdbc;
    private AdminAccount manager;
    private AdminAccount master;
    private Member owner;
    private String managerAccess;
    private String masterAccess;
    private String userAccess;
    private long postNumber;

    @BeforeEach
    void setup() {
        String hash = passwords.encode(PASSWORD);
        manager = accounts.saveAndFlush(new AdminAccount("manager-" + UUID.randomUUID(), hash, AdminRole.MANAGER));
        master = accounts.saveAndFlush(new AdminAccount("master-" + UUID.randomUUID(), hash, AdminRole.MASTER));
        managerAccess = sessions.login(manager.getLoginId(), PASSWORD).tokens().accessToken();
        masterAccess = sessions.login(master.getLoginId(), PASSWORD).tokens().accessToken();
        owner = members.saveAndFlush(new Member("content-" + UUID.randomUUID().toString().substring(0, 12), null, MemberStatus.ACTIVE, null, null));
        owner.completeProfile("Blog " + owner.getId(), "owner-" + owner.getId(), owner.getNickname(), Instant.now());
        members.flush();
        userAccess = accessTokens.issueForMember(owner.getId(), Instant.now());
    }

    @AfterEach
    void cleanup() {
        refreshTokens.revokeAll(manager.getId());
        refreshTokens.revokeAll(master.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"posts", "projects"})
    void managersCanInspectAllContentAndOwnerStatesWithSafeDetailAndSummary(String type) throws Exception {
        long visible = create(type, "PUBLIC", "visible");
        create(type, "HIDDEN", "hidden");
        long blocked = create(type, "PUBLIC", "blocked");
        long deleted = create(type, "PUBLIC", "deleted");
        setBlocked(type, blocked, true);
        forceDelete(type, deleted);
        jdbc.update("update member set status='WITHDRAWAL_PENDING',withdrawal_requested_at=now(),"
                + "withdrawal_scheduled_at=now()+interval '7 days' where id=?", owner.getId());
        entities.clear();
        for (String token : new String[]{managerAccess, masterAccess}) {
            mvc.perform(get(base(type)).param(ownerKey(type), owner.getId().toString()).header("Authorization", bearer(token)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(4))
                    .andExpect(jsonPath("$.data.content[0].content.blocks").doesNotExist())
                    .andExpect(jsonPath("$.data.content[0].content.features").doesNotExist());
            var detail = mvc.perform(get(base(type) + "/" + deleted).header("Authorization", bearer(token)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.content.visibilityStatus").value("DELETED"))
                    .andExpect(jsonPath("$.data.deletedAt").isNotEmpty()).andReturn();
            assertThat(detail.getResponse().getContentAsString()).contains("protected-body")
                    .doesNotContain("providerSubject", "passwordHash", "refreshToken");
        }
        mvc.perform(get(base(type)).param(ownerKey(type), owner.getId().toString()).param("isBlocked", "true")
                        .header("Authorization", bearer(managerAccess)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].blockedByAdminId").value(manager.getId().toString()));
        mvc.perform(get(base(type)).param(ownerKey(type), owner.getId().toString()).param("visibilityStatus", "DELETED")
                        .header("Authorization", bearer(managerAccess)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get("/api/v1/" + type + "/" + visible)).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"posts", "projects"})
    void blockingSuppressesPublicCommentsAndLikesAndUnblockingRestoresTheirHistory(String type) throws Exception {
        long id = create(type, "PUBLIC", "moderated");
        String publicPath = "/api/v1/" + type + "/" + id;
        mvc.perform(put(publicPath + "/like").header("Authorization", bearer(userAccess))).andExpect(status().isNoContent());
        mvc.perform(post(publicPath + "/comments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"retained comment\",\"visibility\":\"PUBLIC\"}")
                        .header("Authorization", bearer(userAccess))).andExpect(status().isCreated());
        mvc.perform(get(base(type) + "/" + id).header("Authorization", bearer(managerAccess)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content.likeCount").value(1))
                .andExpect(jsonPath("$.data.content.likedByMe").value(false));
        var first = data(mvc.perform(put(base(type) + "/" + id + "/block").header("Authorization", bearer(managerAccess)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content.isBlocked").value(true)).andReturn());
        var repeated = data(mvc.perform(put(base(type) + "/" + id + "/block").header("Authorization", bearer(masterAccess)))
                .andExpect(status().isOk()).andReturn());
        assertThat(repeated.path("blockedAt")).isEqualTo(first.path("blockedAt"));
        assertThat(repeated.path("blockedByAdminId").asText()).isEqualTo(manager.getId().toString());
        assertThat(repeated.path("content").path("updatedAt")).isEqualTo(first.path("content").path("updatedAt"));
        assertThat(repeated.path("content").path("visibilityStatus").asText()).isEqualTo("PUBLIC");
        assertThat(repeated.path("content").path("likeCount").asInt()).isZero();
        mvc.perform(get(publicPath)).andExpect(status().isNotFound());
        mvc.perform(get(publicPath + "/comments")).andExpect(status().isNotFound());
        mvc.perform(put(publicPath + "/like").header("Authorization", bearer(userAccess))).andExpect(status().isNotFound());
        mvc.perform(get(publicPath).header("Authorization", bearer(userAccess))).andExpect(status().isOk());
        mvc.perform(delete(base(type) + "/" + id + "/block").header("Authorization", bearer(masterAccess)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content.isBlocked").value(false))
                .andExpect(jsonPath("$.data.blockedAt").isEmpty()).andExpect(jsonPath("$.data.blockedByAdminId").isEmpty());
        mvc.perform(get(publicPath)).andExpect(status().isOk()).andExpect(jsonPath("$.data.likeCount").value(1));
        mvc.perform(get(publicPath + "/comments")).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(delete(base(type) + "/" + id).header("Authorization", bearer(managerAccess))).andExpect(status().isNoContent());
        var deleted = data(mvc.perform(get(base(type) + "/" + id).header("Authorization", bearer(managerAccess)))
                .andExpect(status().isOk()).andReturn());
        mvc.perform(delete(base(type) + "/" + id).header("Authorization", bearer(masterAccess))).andExpect(status().isNoContent());
        var again = data(mvc.perform(get(base(type) + "/" + id).header("Authorization", bearer(managerAccess))).andReturn());
        assertThat(again.path("deletedAt")).isEqualTo(deleted.path("deletedAt"));
        for (boolean block : new boolean[]{true, false}) {
            mvc.perform((block ? put(base(type) + "/" + id + "/block") : delete(base(type) + "/" + id + "/block"))
                            .header("Authorization", bearer(managerAccess)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CONTENT_DELETED"));
        }
        mvc.perform(get(publicPath).header("Authorization", bearer(userAccess))).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"posts", "projects"})
    void literalSearchAndStablePaginationWorkAcrossHiddenContent(String type) throws Exception {
        String term = "needle%_\\";
        long first = create(type, "HIDDEN", term + " one");
        long second = create(type, "PUBLIC", term + " two");
        create(type, "PUBLIC", "needleZZ two");
        var timestamp = java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        jdbc.update("update " + (type.equals("posts") ? "post" : "project") + " set created_at=? where id in (?,?)", timestamp, first, second);
        for (String sort : new String[]{"createdAt,asc", "createdAt,desc"}) {
            long expected = sort.endsWith("asc") ? Math.min(first, second) : Math.max(first, second);
            mvc.perform(get(base(type)).param("q", "  NEEDLE%_\\  ").param(ownerKey(type), owner.getId().toString())
                            .param("sort", sort).param("size", "1").header("Authorization", bearer(managerAccess)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
                    .andExpect(jsonPath("$.data.totalPages").value(2)).andExpect(jsonPath("$.data.hasNext").value(true))
                    .andExpect(jsonPath("$.data.content[0].content.id").value(Long.toString(expected)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"posts", "projects"})
    void validatesInputAndRestrictsRolesCookiesRoutesAndCors(String type) throws Exception {
        long id = create(type, "PUBLIC", "validate");
        mvc.perform(get(base(type))).andExpect(status().isUnauthorized());
        mvc.perform(get(base(type)).cookie(new jakarta.servlet.http.Cookie("admin_refresh_token", "not-access")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(base(type)).header("Authorization", bearer(userAccess))).andExpect(status().isForbidden());
        mvc.perform(put(base(type) + "/" + id + "/block").header("Authorization", bearer(userAccess))).andExpect(status().isForbidden());
        mvc.perform(delete(base(type) + "/" + id).header("Authorization", bearer(userAccess))).andExpect(status().isForbidden());
        mvc.perform(put(base(type) + "/" + id + "/block").cookie(new jakarta.servlet.http.Cookie("admin_refresh_token", "not-access")))
                .andExpect(status().isForbidden());
        for (String query : new String[]{"unknown=1", "page=0&page=1", "visibilityStatus=ACTIVE", "isBlocked=TRUE",
                "size=101", "page=2147483647&size=100", "sort=id,asc", "sort=createdAt,ASC", ownerKey(type) + "=0"}) {
            mvc.perform(get(base(type) + "?" + query).header("Authorization", bearer(managerAccess))).andExpect(status().isBadRequest());
        }
        for (String q : new String[]{" ", "\0", "\uD800", "x".repeat(201)}) {
            mvc.perform(get(base(type)).param("q", q).header("Authorization", bearer(managerAccess))).andExpect(status().isBadRequest());
        }
        for (String badId : new String[]{"0", "01", "9223372036854775808"}) {
            mvc.perform(get(base(type) + "/" + badId).header("Authorization", bearer(managerAccess))).andExpect(status().isBadRequest());
        }
        mvc.perform(get(base(type) + "/abc").header("Authorization", bearer(managerAccess))).andExpect(status().isForbidden());
        mvc.perform(get(base(type) + "/" + Long.MAX_VALUE).header("Authorization", bearer(managerAccess))).andExpect(status().isNotFound());
        mvc.perform(get(base(type)).content("body").header("Authorization", bearer(managerAccess))).andExpect(status().isBadRequest());
        mvc.perform(put(base(type) + "/" + id + "/block").content("{}").header("Authorization", bearer(managerAccess)))
                .andExpect(status().isBadRequest());
        mvc.perform(delete(base(type) + "/" + id + "?q=x").header("Authorization", bearer(managerAccess)))
                .andExpect(status().isBadRequest());
        mvc.perform(options(base(type) + "/" + id + "/block").header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT").header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"));
        mvc.perform(options(base(type)).header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT")) .andExpect(status().isForbidden());
        mvc.perform(options(base(type)).header(HttpHeaders.ORIGIN, "https://foreign.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")) .andExpect(status().isForbidden());
    }

    private long create(String type, String visibility, String title) {
        if (type.equals("posts")) {
            Post post = posts.saveAndFlush(new Post(owner, null, title, null, PostVisibility.valueOf(visibility), "slug-" + ++postNumber, postNumber));
            blocks.saveAndFlush(new PostBlock(post, BlockType.TEXT, "protected-body", null, null, 0));
            return post.getId();
        }
        return projects.saveAndFlush(new Project(owner, title, null, "protected-body", null, null,
                ProjectLifecycleStatus.IN_PROGRESS, null, null, ProjectVisibility.valueOf(visibility))).getId();
    }
    private void setBlocked(String type, long id, boolean blocked) {
        if (type.equals("posts")) management.blockPost(manager.getId(), id, blocked);
        else management.blockProject(manager.getId(), id, blocked);
    }
    private void forceDelete(String type, long id) {
        if (type.equals("posts")) management.deletePost(manager.getId(), id);
        else management.deleteProject(manager.getId(), id);
    }
    private String base(String type) { return "/api/v1/admin/" + type; }
    private String ownerKey(String type) { return type.equals("posts") ? "authorId" : "ownerId"; }
    private String bearer(String token) { return "Bearer " + token; }
    private JsonNode data(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString()).path("data");
    }
}
