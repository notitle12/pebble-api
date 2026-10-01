package com.pebble.api.post.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.auth.infrastructure.naver.NaverOAuthGateway;
import com.pebble.api.member.application.MemberProfileService;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PostProjectIntegrationTest extends AuthenticationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MemberRepository members;
    @Autowired MemberProfileService profiles;
    @Autowired AccessTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockitoBean NaverOAuthGateway naver;
    Member owner;
    Member other;

    @BeforeEach
    void setup() { owner = member(); other = member(); }

    @Test
    void assignsChangesPreservesAndClearsProjectWithoutChangingBoard() throws Exception {
        String first = project(owner, "HIDDEN");
        String second = project(owner, "PUBLIC");
        String board = id(mvc.perform(post("/api/v1/boards").header(HttpHeaders.AUTHORIZATION, bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"board\"}")).andExpect(status().isCreated()));
        String postId = id(createPost(owner, "PUBLIC", first, board).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.projectId").value(first)));
        change(postId, "{\"title\":\"changed\"}").andExpect(status().isOk()).andExpect(jsonPath("$.data.projectId").value(first));
        change(postId, "{\"projectId\":\"" + second + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectId").value(second)).andExpect(jsonPath("$.data.boardId").value(board));
        change(postId, "{\"projectId\":null}").andExpect(status().isOk()).andExpect(jsonPath("$.data.projectId").isEmpty());
        mvc.perform(get("/api/v1/posts/" + postId)).andExpect(status().isOk()).andExpect(jsonPath("$.data.boardId").value(board));
    }

    @Test
    void rejectsForeignDeletedAndMissingProjectsAndInvalidIdFormats() throws Exception {
        String foreign = project(other, "PUBLIC");
        String deleted = project(owner, "PUBLIC");
        mvc.perform(delete("/api/v1/projects/" + deleted).header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isNoContent());
        for (String invalid : new String[]{foreign, deleted, "999999999"}) {
            createPost(owner, "PUBLIC", invalid, null).andExpect(status().isNotFound());
        }
        String existing = id(createPost(owner, "PUBLIC", null, null).andExpect(status().isCreated()));
        change(existing, "{\"projectId\":\"" + foreign + "\"}").andExpect(status().isNotFound());
        for (String value : new String[]{"1", "\"0\"", "\"-1\"", "\"9223372036854775808\"", "true", "[]"}) {
            change(existing, "{\"projectId\":" + value + "}").andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/posts/" + existing)).andExpect(jsonPath("$.data.projectId").isEmpty());
    }

    @Test
    void projectVisibilityDoesNotChangePostVisibilityButRelatedListRequiresPublicProject() throws Exception {
        String project = project(owner, "HIDDEN");
        String visible = id(createPost(owner, "PUBLIC", project, null).andExpect(status().isCreated()));
        String hidden = id(createPost(owner, "HIDDEN", project, null).andExpect(status().isCreated()));
        String blocked = id(createPost(owner, "PUBLIC", project, null).andExpect(status().isCreated()));
        String deleted = id(createPost(owner, "PUBLIC", project, null).andExpect(status().isCreated()));
        mvc.perform(delete("/api/v1/posts/" + deleted).header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isNoContent());
        jdbc.update("update post set is_blocked=true, blocked_at=now(), blocked_by_admin_id=1 where id=?", Long.parseLong(blocked));
        em.clear();
        mvc.perform(get("/api/v1/posts/" + visible)).andExpect(status().isOk()).andExpect(jsonPath("$.data.projectId").value(project));
        mvc.perform(get("/api/v1/projects/" + project + "/posts")).andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/projects/" + project).header(HttpHeaders.AUTHORIZATION, bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"visibilityStatus\":\"PUBLIC\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/projects/" + project + "/posts").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(visible)).andExpect(jsonPath("$.data.content[0].blocks").doesNotExist());
        mvc.perform(get("/api/v1/posts/" + hidden)).andExpect(status().isNotFound());
        jdbc.update("update project set is_blocked=true, blocked_at=now(), blocked_by_admin_id=1 where id=?", Long.parseLong(project));
        em.clear();
        mvc.perform(get("/api/v1/projects/" + project + "/posts").header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isNotFound());
        jdbc.update("update project set is_blocked=false, blocked_at=null, blocked_by_admin_id=null where id=?", Long.parseLong(project));
        jdbc.update("update member set status='WITHDRAWAL_PENDING', withdrawal_requested_at=now(), withdrawal_scheduled_at=now()+interval '7 days' where id=?", owner.getId());
        em.clear();
        mvc.perform(get("/api/v1/projects/" + project + "/posts")).andExpect(status().isNotFound());
    }

    @Test
    void deletingProjectDetachesEveryPostAndPreservesSearchAndHiddenStatus() throws Exception {
        String project = project(owner, "PUBLIC");
        String visible = id(createPost(owner, "PUBLIC", project, null).andExpect(status().isCreated()));
        String hidden = id(createPost(owner, "HIDDEN", project, null).andExpect(status().isCreated()));
        String deleted = id(createPost(owner, "PUBLIC", project, null).andExpect(status().isCreated()));
        mvc.perform(delete("/api/v1/posts/" + deleted).header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/projects/" + project).header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isNoContent());
        em.clear();
        assertThat(jdbc.queryForObject("select count(*) from post where project_id=?", Long.class, Long.parseLong(project))).isZero();
        for (String post : new String[]{visible, hidden, deleted}) {
            assertThat(jdbc.queryForObject("select title from post where id=?", String.class, Long.parseLong(post))).isEqualTo("project-link needle");
        }
        assertThat(jdbc.queryForObject("select visibility_status from post where id=?", String.class, Long.parseLong(hidden))).isEqualTo("HIDDEN");
        assertThat(jdbc.queryForObject("select visibility_status from post where id=?", String.class, Long.parseLong(deleted))).isEqualTo("DELETED");
        mvc.perform(get("/api/v1/posts/search").param("q", "project-link needle")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1)).andExpect(jsonPath("$.data.content[0].projectId").isEmpty());
        mvc.perform(get("/api/v1/projects/" + project + "/posts")).andExpect(status().isNotFound());
        change(visible, "{\"projectId\":\"" + project + "\"}").andExpect(status().isNotFound());
    }

    @Test
    void relatedListValidatesQueriesAndCors() throws Exception {
        String project = project(owner, "PUBLIC");
        String path = "/api/v1/projects/" + project + "/posts";
        for (String[] parameter : new String[][]{{"projectId", project}, {"sort", "name,asc"}, {"size", "101"}, {"tagId", "1"}}) {
            mvc.perform(get(path).param(parameter[0], parameter[1])).andExpect(status().isBadRequest());
        }
        mvc.perform(get(path).param("size", "1", "2")).andExpect(status().isBadRequest());
        mvc.perform(options(path).header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET"));
        mvc.perform(get("/api/v1/projects/999999999/posts")).andExpect(status().isNotFound());
    }

    @Test
    void compositeForeignKeyRejectsForeignOwnerEvenWithoutApplicationValidation() throws Exception {
        String project = project(other, "PUBLIC");
        String post = id(createPost(owner, "PUBLIC", null, null).andExpect(status().isCreated()));
        assertThatThrownBy(() -> jdbc.update("update post set project_id=? where id=?", Long.parseLong(project), Long.parseLong(post)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void compositeForeignKeyRejectsMissingProject() throws Exception {
        String post = id(createPost(owner, "PUBLIC", null, null).andExpect(status().isCreated()));
        assertThatThrownBy(() -> jdbc.update("update post set project_id=999999999 where id=?", Long.parseLong(post)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Member member() {
        Member value = members.saveAndFlush(new Member("project-link-" + UUID.randomUUID().toString().substring(0, 8), null, MemberStatus.ACTIVE, null, null));
        return profiles.complete(value.getId(), "blog-" + value.getId(), "author-" + value.getId(), null);
    }
    private String bearer(Member member) { return "Bearer " + tokens.issueForMember(member.getId(), java.time.Instant.now()); }
    private String id(ResultActions result) throws Exception { return mapper.readTree(result.andReturn().getResponse().getContentAsString()).at("/data/id").asText(); }
    private String project(Member member, String visibility) throws Exception {
        return id(mvc.perform(post("/api/v1/projects").header(HttpHeaders.AUTHORIZATION, bearer(member))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"linked project\",\"lifecycleStatus\":\"IN_PROGRESS\",\"visibilityStatus\":\"" + visibility + "\"}"))
                .andExpect(status().isCreated()));
    }
    private ResultActions createPost(Member member, String visibility, String project, String board) throws Exception {
        var body = mapper.createObjectNode().put("title", "project-link needle").put("visibilityStatus", visibility);
        if (project != null) body.put("projectId", project);
        if (board != null) body.put("boardId", board);
        body.putArray("blocks").addObject().put("type", "TEXT").put("content", "preserved body");
        return mvc.perform(post("/api/v1/posts").header(HttpHeaders.AUTHORIZATION, bearer(member))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }
    private ResultActions change(String post, String body) throws Exception {
        return mvc.perform(patch("/api/v1/posts/" + post).header(HttpHeaders.AUTHORIZATION, bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
