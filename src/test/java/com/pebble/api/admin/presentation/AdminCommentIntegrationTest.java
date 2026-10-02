package com.pebble.api.admin.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.admin.domain.AdminAccount;
import com.pebble.api.admin.domain.AdminRole;
import com.pebble.api.admin.domain.AdminStatus;
import com.pebble.api.admin.infrastructure.persistence.AdminAccountRepository;
import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.auth.application.AdminRefreshTokenService;
import com.pebble.api.auth.application.AdminSessionService;
import com.pebble.api.comment.domain.CommentVisibility;
import com.pebble.api.comment.domain.PostComment;
import com.pebble.api.comment.domain.ProjectComment;
import com.pebble.api.comment.infrastructure.persistence.PostCommentRepository;
import com.pebble.api.comment.infrastructure.persistence.ProjectCommentRepository;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.post.domain.Post;
import com.pebble.api.post.domain.PostVisibility;
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
import org.junit.jupiter.api.Test;
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
class AdminCommentIntegrationTest extends AuthenticationTestSupport {
    private static final String PASSWORD = "Comment-Moderation-Test-2026";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AdminAccountRepository accounts;
    @Autowired AdminSessionService sessions;
    @Autowired AdminRefreshTokenService refreshTokens;
    @Autowired PasswordEncoder passwords;
    @Autowired AccessTokenService accessTokens;
    @Autowired MemberRepository members;
    @Autowired PostRepository posts;
    @Autowired ProjectRepository projects;
    @Autowired PostCommentRepository postComments;
    @Autowired ProjectCommentRepository projectComments;
    @Autowired EntityManager entities;
    @Autowired JdbcTemplate jdbc;
    private AdminAccount manager;
    private AdminAccount master;
    private Member author;
    private String managerAccess;
    private String masterAccess;
    private String userAccess;
    private long postSequence;

    @BeforeEach
    void setup() {
        String hash = passwords.encode(PASSWORD);
        manager = accounts.saveAndFlush(new AdminAccount("manager-" + UUID.randomUUID(), hash, AdminRole.MANAGER));
        master = accounts.saveAndFlush(new AdminAccount("master-" + UUID.randomUUID(), hash, AdminRole.MASTER));
        managerAccess = sessions.login(manager.getLoginId(), PASSWORD).tokens().accessToken();
        masterAccess = sessions.login(master.getLoginId(), PASSWORD).tokens().accessToken();
        author = members.saveAndFlush(new Member("comment-" + UUID.randomUUID().toString().substring(0, 12), null, MemberStatus.ACTIVE, null, null));
        author.completeProfile("Comment blog", "comment-author", author.getNickname(), Instant.now());
        members.flush();
        userAccess = accessTokens.issueForMember(author.getId(), Instant.now());
        postSequence = 0;
    }

    @AfterEach
    void cleanup() {
        refreshTokens.revokeAll(manager.getId());
        refreshTokens.revokeAll(master.getId());
    }

    @Test
    void managersCanFilterPaginateAndInspectSecretAndDeletedCommentsWithoutDeletedBody() throws Exception {
        long postId = posts.saveAndFlush(new Post(author, null, "post", null, PostVisibility.HIDDEN, "comment-post-" + UUID.randomUUID(), 1)).getId();
        long projectId = projects.saveAndFlush(new Project(author, "project", null, "private project body", null, null,
                ProjectLifecycleStatus.IN_PROGRESS, null, null, ProjectVisibility.HIDDEN)).getId();
        var p1 = postComments.saveAndFlush(new PostComment(postId, author, "secret retained text", CommentVisibility.SECRET));
        var p2 = postComments.saveAndFlush(new PostComment(postId, author, "public retained text", CommentVisibility.PUBLIC));
        var p3 = postComments.saveAndFlush(new PostComment(postId, author, "to be deleted confidential text", CommentVisibility.SECRET));
        var foreign = projectComments.saveAndFlush(new ProjectComment(projectId, author, "other target", CommentVisibility.PUBLIC));
        jdbc.update("update post_comment set created_at=timestamp '2026-01-01 00:00:00',updated_at=timestamp '2026-01-01 00:00:00' where id in (?,?)", p1.getId(), p2.getId());
        jdbc.update("update post_comment set created_at=timestamp '2026-01-02 00:00:00',updated_at=timestamp '2026-01-02 00:00:00' where id=?", p3.getId());
        postComments.flush();
        entities.clear();
        String path = "/api/v1/admin/post-comments";

        for (String token : new String[]{managerAccess, masterAccess}) {
            var response = mvc.perform(get(path).header("Authorization", bearer(token)).param("postId", Long.toString(postId))
                            .param("visibility", "SECRET").param("deleted", "false").param("sort", "createdAt,asc").param("size", "1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
                    .andExpect(jsonPath("$.data.totalPages").value(2)).andExpect(jsonPath("$.data.hasNext").value(true))
                    .andExpect(jsonPath("$.data.content[0].id").value(p1.getId().toString())).andReturn();
            assertThat(response.getResponse().getContentAsString()).contains("secret retained text")
                    .doesNotContain("private project body", "providerSubject", "passwordHash", "refreshToken");
        }
        mvc.perform(get(path).header("Authorization", bearer(managerAccess)).param("postId", Long.toString(postId))
                        .param("sort", "createdAt,desc").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].id").value(p3.getId().toString()));
        mvc.perform(get(path).header("Authorization", bearer(managerAccess)).param("authorId", author.getId().toString())
                        .param("deleted", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(get("/api/v1/admin/project-comments").header("Authorization", bearer(managerAccess)).param("projectId", Long.toString(projectId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(foreign.getId().toString()));

        mvc.perform(delete(path + "/" + p3.getId()).header("Authorization", bearer(managerAccess)))
                .andExpect(status().isNoContent());
        entities.clear();
        var deleted = mvc.perform(get(path).header("Authorization", bearer(managerAccess)).param("postId", Long.toString(postId))
                        .param("deleted", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].deletedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.content[0].body").doesNotExist()).andReturn();
        assertThat(deleted.getResponse().getContentAsString()).doesNotContain("to be deleted confidential text");
        JsonNode first = data(deleted).path("content").get(0);
        mvc.perform(delete(path + "/" + p3.getId()).header("Authorization", bearer(masterAccess))).andExpect(status().isNoContent());
        entities.clear();
        JsonNode again = data(mvc.perform(get(path).header("Authorization", bearer(managerAccess)).param("postId", Long.toString(postId))
                .param("deleted", "true")).andReturn()).path("content").get(0);
        assertThat(again.path("deletedAt")).isEqualTo(first.path("deletedAt"));
        assertThat(again.path("updatedAt")).isEqualTo(first.path("updatedAt"));
    }

    @Test
    void validatesRoutesRolesQueriesIdsBodiesAndCors() throws Exception {
        long postId = posts.saveAndFlush(new Post(author, null, "post", null, PostVisibility.PUBLIC, "comment-post-" + UUID.randomUUID(), 1)).getId();
        var comment = postComments.saveAndFlush(new PostComment(postId, author, "safe", CommentVisibility.PUBLIC));
        String path = "/api/v1/admin/post-comments";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", bearer(userAccess))).andExpect(status().isForbidden());
        mvc.perform(delete(path + "/" + comment.getId()).header("Authorization", bearer(userAccess))).andExpect(status().isForbidden());
        for (String query : new String[]{"unknown=1", "page=0&page=1", "visibility=PRIVATE", "deleted=TRUE", "size=101",
                "page=2147483647&size=100", "sort=id,asc", "sort=createdAt,ASC", "postId=0", "authorId=01"}) {
            mvc.perform(get(path + "?" + query).header("Authorization", bearer(managerAccess))).andExpect(status().isBadRequest());
        }
        for (String id : new String[]{"0", "01", "9223372036854775808"}) {
            mvc.perform(get(path).param("postId", id).header("Authorization", bearer(managerAccess))).andExpect(status().isBadRequest());
            mvc.perform(delete(path + "/" + id).header("Authorization", bearer(managerAccess))).andExpect(status().isBadRequest());
        }
        mvc.perform(get(path).content("body").header("Authorization", bearer(managerAccess))).andExpect(status().isBadRequest());
        mvc.perform(delete(path + "/" + comment.getId()).contentType(MediaType.APPLICATION_JSON).content("{}").header("Authorization", bearer(managerAccess)))
                .andExpect(status().isBadRequest());
        mvc.perform(delete(path + "/" + comment.getId() + "?x=1").header("Authorization", bearer(managerAccess))).andExpect(status().isBadRequest());
        mvc.perform(delete(path + "/9223372036854775807").header("Authorization", bearer(managerAccess))).andExpect(status().isNotFound());
        mvc.perform(delete(path + "/abc").header("Authorization", bearer(managerAccess))).andExpect(status().isForbidden());
        mvc.perform(delete(path + "/" + comment.getId()).cookie(new jakarta.servlet.http.Cookie("admin_refresh_token", "not-access")))
                .andExpect(status().isForbidden());
        mvc.perform(get(path + "/" + comment.getId()).header("Authorization", bearer(managerAccess))).andExpect(status().isForbidden());
        mvc.perform(post(path).header("Authorization", bearer(managerAccess))).andExpect(status().isForbidden());
        mvc.perform(patch(path + "/" + comment.getId()).header("Authorization", bearer(managerAccess))).andExpect(status().isForbidden());
        mvc.perform(options(path).header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, org.hamcrest.Matchers.containsString("GET")));
        mvc.perform(options(path + "/" + comment.getId()).header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "DELETE"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, org.hamcrest.Matchers.containsString("DELETE")));
        mvc.perform(options(path).header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH")).andExpect(status().isForbidden());
        mvc.perform(options(path).header(HttpHeaders.ORIGIN, "https://foreign.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/post-comments/" + comment.getId() + "/detail").header("Authorization", bearer(managerAccess)))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/admin/project-comments/" + comment.getId()).header("Authorization", bearer(managerAccess)))
                .andExpect(status().isNotFound());
        refreshTokens.revokeAll(manager.getId());
        mvc.perform(get(path).header("Authorization", bearer(managerAccess))).andExpect(status().isUnauthorized());
        managerAccess = sessions.login(manager.getLoginId(), PASSWORD).tokens().accessToken();
        manager.changeManagerStatus(AdminStatus.INACTIVE);
        accounts.flush();
        mvc.perform(get(path).header("Authorization", bearer(managerAccess))).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"posts", "projects"})
    void listsAndDeletesCommentsRegardlessOfParentOrAuthorState(String parentType) throws Exception {
        boolean post = parentType.equals("posts");
        String path = "/api/v1/admin/" + (post ? "post-comments" : "project-comments");
        String table = post ? "post" : "project";
        for (String state : new String[]{"PUBLIC", "HIDDEN", "DELETED", "BLOCKED"}) {
            long parentId = createParent(post);
            if (state.equals("DELETED")) {
                jdbc.update("update " + table + " set visibility_status='DELETED',deleted_at=current_timestamp where id=?", parentId);
            } else if (state.equals("HIDDEN")) {
                jdbc.update("update " + table + " set visibility_status='HIDDEN' where id=?", parentId);
            } else if (state.equals("BLOCKED")) {
                jdbc.update("update " + table + " set is_blocked=true,blocked_at=current_timestamp,blocked_by_admin_id=? where id=?", manager.getId(), parentId);
            }
            long id = saveComment(post, parentId, "parent " + state + " private text");
            String filter = post ? "postId" : "projectId";
            mvc.perform(get(path).header("Authorization", bearer(managerAccess)).param(filter, Long.toString(parentId)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content[0].body").value("parent " + state + " private text"));
            mvc.perform(delete(path + "/" + id).header("Authorization", bearer(managerAccess))).andExpect(status().isNoContent());
            assertThat(jdbc.queryForObject("select body from " + (post ? "post_comment" : "project_comment") + " where id=?", String.class, id)).isEmpty();
        }
        for (String state : new String[]{"SUSPENDED", "WITHDRAWAL_PENDING"}) {
            jdbc.update("update member set status=?,withdrawal_requested_at=case when ?='WITHDRAWAL_PENDING' then current_timestamp end, "
                    + "withdrawal_scheduled_at=case when ?='WITHDRAWAL_PENDING' then current_timestamp + interval '7 days' end where id=?",
                    state, state, state, author.getId());
            long parentId = createParent(post);
            long id = saveComment(post, parentId, "author " + state + " text");
            entities.clear();
            mvc.perform(get(path).header("Authorization", bearer(masterAccess)).param(post ? "postId" : "projectId", Long.toString(parentId)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
            mvc.perform(delete(path + "/" + id).header("Authorization", bearer(masterAccess))).andExpect(status().isNoContent());
            jdbc.update("update member set status='ACTIVE',withdrawal_requested_at=null,withdrawal_scheduled_at=null where id=?", author.getId());
        }
    }

    @Test
    void listsSoftDeletedCommentsByDefaultAndOrdersEqualTimestampsById() throws Exception {
        long parentId = createParent(true);
        long firstId = saveComment(true, parentId, "first body");
        long secondId = saveComment(true, parentId, "second body");
        jdbc.update("update post_comment set created_at=timestamp '2026-01-01 00:00:00',updated_at=timestamp '2026-01-01 00:00:00' where id in (?,?)", firstId, secondId);
        mvc.perform(delete("/api/v1/admin/post-comments/" + firstId).header("Authorization", bearer(managerAccess))).andExpect(status().isNoContent());
        entities.clear();
        mvc.perform(get("/api/v1/admin/post-comments").header("Authorization", bearer(managerAccess)).param("postId", Long.toString(parentId))
                        .param("sort", "createdAt,asc").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].id").value(Long.toString(Math.min(firstId, secondId))))
                .andExpect(jsonPath("$.data.content[0].body").doesNotExist()).andExpect(jsonPath("$.data.hasNext").value(true));
        mvc.perform(get("/api/v1/admin/post-comments").header("Authorization", bearer(managerAccess)).param("postId", Long.toString(parentId))
                        .param("sort", "createdAt,asc").param("size", "1").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].id").value(Long.toString(Math.max(firstId, secondId))))
                .andExpect(jsonPath("$.data.hasNext").value(false)).andExpect(jsonPath("$.data.hasPrevious").value(true));
        mvc.perform(get("/api/v1/admin/post-comments").header("Authorization", bearer(managerAccess)).param("postId", Long.toString(parentId))
                        .param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].id").value(Long.toString(Math.max(firstId, secondId))));
        mvc.perform(get("/api/v1/admin/post-comments").header("Authorization", bearer(managerAccess)).param("postId", Long.toString(parentId))
                        .param("deleted", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
    }

    private long createParent(boolean post) {
        if (post) return posts.saveAndFlush(new Post(author, null, "post", null, PostVisibility.PUBLIC, "comment-post-" + UUID.randomUUID(), ++postSequence)).getId();
        return projects.saveAndFlush(new Project(author, "project", null, "private project body", null, null,
                ProjectLifecycleStatus.IN_PROGRESS, null, null, ProjectVisibility.PUBLIC)).getId();
    }

    private long saveComment(boolean post, long parentId, String body) {
        return post ? postComments.saveAndFlush(new PostComment(parentId, author, body, CommentVisibility.PUBLIC)).getId()
                : projectComments.saveAndFlush(new ProjectComment(parentId, author, body, CommentVisibility.PUBLIC)).getId();
    }

    private String bearer(String token) { return "Bearer " + token; }
    private JsonNode data(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString()).path("data");
    }
}
