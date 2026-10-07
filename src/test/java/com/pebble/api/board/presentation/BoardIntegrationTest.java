package com.pebble.api.board.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.auth.application.oauth.OAuthProviderClient;
import com.pebble.api.member.application.MemberProfileService;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
class BoardIntegrationTest extends AuthenticationTestSupport {
    private static final String BOARD_PATH = "/api/v1/boards";
    private static final String POSTS_PATH = "/api/v1/posts";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MemberRepository members;
    @Autowired MemberProfileService profiles;
    @Autowired AccessTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockitoBean(name = "naverOAuthClient") OAuthProviderClient naver;
    Member owner;
    Member other;

    @BeforeEach
    void setup() {
        owner = initializedMember("owner");
        other = initializedMember("other");
    }

    @Test
    void createsAndReturnsSortedStringIdTreesWithDistinctPublicShape() throws Exception {
        String first = create(owner, "first", null, 4);
        String second = create(owner, "second", null, 4);
        String child = create(owner, "child", first, 0);
        int firstIndex = Long.parseLong(first) < Long.parseLong(second) ? 0 : 1;
        int secondIndex = 1 - firstIndex;
        var privateTree = get("/api/v1/members/me/boards").header(HttpHeaders.AUTHORIZATION, bearer(owner));
        mvc.perform(privateTree).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[" + firstIndex + "].id").value(first))
                .andExpect(jsonPath("$.data[" + secondIndex + "].id").value(second))
                .andExpect(jsonPath("$.data[" + firstIndex + "].parentId").isEmpty())
                .andExpect(jsonPath("$.data[" + firstIndex + "].children[0].id").value(child))
                .andExpect(jsonPath("$.data[" + firstIndex + "].children[0].parentId").value(first));
        mvc.perform(get("/api/v1/members/" + owner.getId() + "/boards"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[" + firstIndex + "].parentId").doesNotExist())
                .andExpect(jsonPath("$.data[" + firstIndex + "].children[0].parentId").doesNotExist())
                .andExpect(jsonPath("$.data[" + firstIndex + "].children[0].id").value(child));
        mvc.perform(get("/api/v1/members/" + other.getId() + "/boards"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/api/v1/members/me/boards").header(HttpHeaders.AUTHORIZATION, bearer(owner)).param("sort", "name"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/members/" + owner.getId() + "/boards").param("page", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validatesBoardRequestFieldsAndUsesDefaultOrder() throws Exception {
        String created = create(owner, "😀".repeat(50), null, null);
        mvc.perform(get("/api/v1/members/me/boards").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(jsonPath("$.data[0].id").value(created)).andExpect(jsonPath("$.data[0].displayOrder").value(0));
        for (String bad : new String[]{"{}", "[]", "null", "{\"name\":null}", "{\"name\":\" \"}",
                "{\"name\":\"x\",\"displayOrder\":null}", "{\"name\":\"x\",\"displayOrder\":-1}",
                "{\"name\":\"x\",\"displayOrder\":1.5}", "{\"name\":\"x\",\"displayOrder\":\"1\"}",
                "{\"name\":\"x\",\"displayOrder\":2147483648}", "{\"name\":\"x\",\"parentId\":1}",
                "{\"name\":\"x\",\"parentId\":\"0\"}", "{\"name\":\"x\",\"unknown\":true}"}) {
            writeCreate(owner, bad).andExpect(status().isBadRequest());
        }
        writeCreate(owner, "{\"name\":\"" + "x".repeat(51) + "\"}").andExpect(status().isBadRequest());
        writeCreate(owner, "{\"name\":\"\\u0000\"}").andExpect(status().isBadRequest());
        for (String id : new String[]{"0", "01", "9223372036854775808"}) {
            mvc.perform(get("/api/v1/members/" + id + "/boards")).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/members/-1/boards")).andExpect(status().isUnauthorized());
        mvc.perform(post(BOARD_PATH).header(HttpHeaders.AUTHORIZATION, bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"numeric id\",\"parentId\":\"" + other.getId() + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void patchDistinguishesOmissionAndNullParentAndRejectsInvalidValues() throws Exception {
        String root = create(owner, "root", null, 8);
        String destination = create(owner, "destination", null, 0);
        String child = create(owner, "child", root, 2);
        writePatch(child, "{\"name\":\"renamed\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parentId").value(root)).andExpect(jsonPath("$.data.displayOrder").value(2));
        writePatch(child, "{\"parentId\":\"" + destination + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parentId").value(destination)).andExpect(jsonPath("$.data.name").value("renamed"));
        writePatch(child, "{\"parentId\":null}").andExpect(status().isOk()).andExpect(jsonPath("$.data.parentId").isEmpty());
        for (String bad : new String[]{"[]", "null", "{\"name\":null}", "{\"name\":\" \"}",
                "{\"parentId\":1}", "{\"displayOrder\":null}", "{\"displayOrder\":-1}", "{\"unknown\":true}"}) {
            writePatch(child, bad).andExpect(status().isBadRequest());
        }
        mvc.perform(patch(BOARD_PATH + "/01").header(HttpHeaders.AUTHORIZATION, bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void enforcesOwnershipSiblingNamesCyclesDepthAndChildDeleteGuard() throws Exception {
        String root = create(owner, "root", null, 0);
        String child = create(owner, "child", root, 0);
        String grandchild = create(owner, "grandchild", child, 0);
        writeCreate(owner, "{\"name\":\"too deep\",\"parentId\":\"" + grandchild + "\"}")
                .andExpect(status().isBadRequest());
        String secondRoot = create(owner, "second", null, 0);
        writePatch(root, "{\"parentId\":\"" + secondRoot + "\"}").andExpect(status().isBadRequest());
        writePatch(root, "{\"parentId\":\"" + child + "\"}").andExpect(status().isBadRequest());
        writePatch(child, "{\"parentId\":\"" + child + "\"}").andExpect(status().isBadRequest());
        writePatch(secondRoot, "{\"name\":\"root\"}").andExpect(status().isBadRequest());
        mvc.perform(delete(BOARD_PATH + "/" + root).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("RESOURCE_HAS_CHILDREN"));
        mvc.perform(delete(BOARD_PATH + "/" + grandchild).header(HttpHeaders.AUTHORIZATION, bearer(other))).andExpect(status().isNotFound());
        mvc.perform(post(BOARD_PATH).header(HttpHeaders.AUTHORIZATION, bearer(other)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"wrong owner parent\",\"parentId\":\"" + root + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletedChildDoesNotPreventParentDeletionAndNamesCanBeReused() throws Exception {
        String root = create(owner, "root", null, 0);
        String child = create(owner, "child", root, 0);
        mvc.perform(delete(BOARD_PATH + "/" + child).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isNoContent());
        mvc.perform(delete(BOARD_PATH + "/" + root).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isNoContent());
        String replacement = create(owner, "root", null, 0);
        assertThat(replacement).isNotEqualTo(root);
        mvc.perform(get("/api/v1/members/me/boards").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].id").value(replacement));
    }

    @Test
    void boardWritesDoNotRequireCompletedProfileButPostWritesStillDo() throws Exception {
        Member incomplete = members.saveAndFlush(new Member("incomplete-" + UUID.randomUUID().toString().substring(0, 8),
                null, MemberStatus.ACTIVE, null, null));
        String id = create(incomplete, "draft tree", null, 0);
        mvc.perform(post(POSTS_PATH).header(HttpHeaders.AUTHORIZATION, bearer(incomplete)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"title\",\"visibilityStatus\":\"PUBLIC\",\"boardId\":\"" + id
                                + "\",\"blocks\":[{\"type\":\"TEXT\",\"content\":\"text\"}]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PROFILE_REQUIRED"));
    }

    @Test
    void requiresActiveUserForPrivateReadsAndWrites() throws Exception {
        String id = create(owner, "active", null, 0);
        mvc.perform(get("/api/v1/members/me/boards")).andExpect(status().isUnauthorized());
        mvc.perform(post(BOARD_PATH).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"guest\"}"))
                .andExpect(status().isForbidden());
        for (String state : new String[]{"SUSPENDED", "WITHDRAWAL_PENDING"}) {
            em.flush();
            jdbc.update("update member set status=?, withdrawal_requested_at=case when ?='WITHDRAWAL_PENDING' then current_timestamp end, "
                    + "withdrawal_scheduled_at=case when ?='WITHDRAWAL_PENDING' then current_timestamp + interval '7 days' end where id=?",
                    state, state, state, owner.getId());
            em.clear();
            mvc.perform(get("/api/v1/members/me/boards").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                    .andExpect(status().isForbidden());
            writePatch(id, "{}").andExpect(status().isForbidden());
            mvc.perform(delete(BOARD_PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isForbidden());
            jdbc.update("update member set status='ACTIVE', withdrawal_requested_at=null, withdrawal_scheduled_at=null where id=?", owner.getId());
            em.clear();
        }
    }

    @Test
    void assignsMovesClearsAndRejectsForeignOrDeletedBoardsOnPosts() throws Exception {
        String first = create(owner, "first", null, 0);
        String second = create(owner, "second", null, 0);
        String postId = createPost(owner, "PUBLIC", "\"" + first + "\"");
        mvc.perform(get(POSTS_PATH + "/" + postId).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.boardId").value(first));
        patchPost(owner, postId, "{\"boardId\":\"" + second + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.boardId").value(second));
        patchPost(owner, postId, "{\"title\":\"kept board\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.boardId").value(second));
        patchPost(owner, postId, "{\"boardId\":null}").andExpect(status().isOk()).andExpect(jsonPath("$.data.boardId").isEmpty());
        patchPost(owner, postId, "{\"boardId\":\"" + first + "\"}").andExpect(status().isOk());
        patchPost(other, postId, "{\"boardId\":\"" + second + "\"}").andExpect(status().isNotFound());
        mvc.perform(delete(BOARD_PATH + "/" + first).header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isNoContent());
        patchPost(owner, postId, "{\"boardId\":\"" + first + "\"}").andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select board_id from post where id=?", Long.class, Long.parseLong(postId))).isNull();
    }

    @Test
    void deletingBoardDetachesPostsAcrossVisibilityAndBlockStates() throws Exception {
        String board = create(owner, "detach", null, 0);
        String hidden = createPost(owner, "PUBLIC", "\"" + board + "\"");
        patchPost(owner, hidden, "{\"visibilityStatus\":\"HIDDEN\"}").andExpect(status().isOk());
        String deleted = createPost(owner, "PUBLIC", "\"" + board + "\"");
        mvc.perform(delete(POSTS_PATH + "/" + deleted).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isNoContent());
        String blocked = createPost(owner, "PUBLIC", "\"" + board + "\"");
        jdbc.update("update post set is_blocked=true, blocked_at=current_timestamp, blocked_by_admin_id=1 where id=?",
                Long.parseLong(blocked));
        mvc.perform(delete(BOARD_PATH + "/" + board).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from post where id in (?,?,?) and board_id is null", Integer.class,
                Long.parseLong(hidden), Long.parseLong(deleted), Long.parseLong(blocked))).isEqualTo(3);
        assertThat(jdbc.queryForList("select visibility_status from post where id in (?,?,?)", String.class,
                Long.parseLong(hidden), Long.parseLong(deleted), Long.parseLong(blocked)))
                .containsExactlyInAnyOrder("HIDDEN", "DELETED", "PUBLIC");
        assertThat(jdbc.queryForObject("select is_blocked from post where id=?", Boolean.class, Long.parseLong(blocked))).isTrue();
    }

    private String create(Member member, String name, String parentId, Integer order) throws Exception {
        String body = "{\"name\":" + mapper.writeValueAsString(name)
                + (parentId == null ? "" : ",\"parentId\":\"" + parentId + "\"")
                + (order == null ? "" : ",\"displayOrder\":" + order) + "}";
        ResultActions result = writeCreate(member, body).andExpect(status().isCreated());
        return response(result).at("/data/id").asText();
    }

    private ResultActions writeCreate(Member member, String body) throws Exception {
        return mvc.perform(post(BOARD_PATH).header(HttpHeaders.AUTHORIZATION, bearer(member))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions writePatch(String id, String body) throws Exception {
        return mvc.perform(patch(BOARD_PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String createPost(Member member, String visibility, String boardJson) throws Exception {
        String body = "{\"title\":\"title\",\"summary\":null,\"visibilityStatus\":\"" + visibility
                + "\",\"blocks\":[{\"type\":\"TEXT\",\"content\":\"text\"}],\"tagIds\":[],\"boardId\":" + boardJson + "}";
        ResultActions result = mvc.perform(post(POSTS_PATH).header(HttpHeaders.AUTHORIZATION, bearer(member))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        return response(result).at("/data/id").asText();
    }

    private ResultActions patchPost(Member member, String id, String body) throws Exception {
        return mvc.perform(patch(POSTS_PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(member))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode response(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private Member initializedMember(String label) {
        Member created = members.saveAndFlush(new Member(label + "-" + UUID.randomUUID().toString().substring(0, 8),
                null, MemberStatus.ACTIVE, null, null));
        return profiles.complete(created.getId(), "blog-" + created.getId(), "author-" + created.getId(), null);
    }

    private String bearer(Member member) {
        return "Bearer " + tokens.issueForMember(member.getId(), Instant.now());
    }
}
