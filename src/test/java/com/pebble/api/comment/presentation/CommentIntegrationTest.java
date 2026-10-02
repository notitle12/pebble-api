package com.pebble.api.comment.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.auth.infrastructure.naver.NaverOAuthGateway;
import com.pebble.api.member.application.MemberProfileService;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
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
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CommentIntegrationTest extends AuthenticationTestSupport {
    private static final String POSTS = "/api/v1/posts";
    private static final String PROJECTS = "/api/v1/projects";
    private static final String ORIGIN = "http://localhost:3000";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MemberRepository members;
    @Autowired MemberProfileService profiles;
    @Autowired AccessTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockitoBean NaverOAuthGateway naver;
    Member owner;
    Member writer;
    Member stranger;

    @BeforeEach
    void setup() {
        owner = initializedMember("owner");
        writer = initializedMember("writer");
        stranger = initializedMember("stranger");
    }

    @Test
    void postAndProjectCommentsSupportCreateListPatchAndLogicalDelete() throws Exception {
        for (String parent : new String[]{createPost(owner, "PUBLIC"), project(owner, "PUBLIC")}) {
            String collection = comments(parent);
            String comment = createComment(collection, writer, "first 😀", "PUBLIC");
            mvc.perform(get(collection)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].id").value(comment))
                    .andExpect(jsonPath("$.data.content[0].body").value("first 😀"))
                    .andExpect(jsonPath("$.data.content[0].visibility").value("PUBLIC"))
                    .andExpect(jsonPath("$.data.content[0].author.id").value(writer.getId().toString()))
                    .andExpect(jsonPath("$.data.page").value(0)).andExpect(jsonPath("$.data.size").value(20))
                    .andExpect(jsonPath("$.data.totalElements").value(1)).andExpect(jsonPath("$.data.hasNext").value(false));
            String item = collection + "/" + comment;
            mvc.perform(get(item)).andExpect(status().isOk()).andExpect(jsonPath("$.data.body").value("first 😀"));
            mvc.perform(patch(item).header(HttpHeaders.AUTHORIZATION, bearer(writer)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"body\":\"changed\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.body").value("changed"))
                    .andExpect(jsonPath("$.data.visibility").value("PUBLIC"));
            mvc.perform(patch(item).header(HttpHeaders.AUTHORIZATION, bearer(writer)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"body\":\"secret now\",\"visibility\":\"SECRET\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.visibility").value("SECRET"));
            mvc.perform(delete(item).header(HttpHeaders.AUTHORIZATION, bearer(writer))).andExpect(status().isNoContent())
                    .andExpect(content().string(""));
            mvc.perform(delete(item).header(HttpHeaders.AUTHORIZATION, bearer(writer))).andExpect(status().isNotFound());
            assertThat(jdbc.queryForObject("select body from " + table(parent) + " where id=?", String.class,
                    Long.parseLong(comment))).isEmpty();
            assertThat(jdbc.queryForObject("select deleted_at is not null from " + table(parent) + " where id=?", Boolean.class,
                    Long.parseLong(comment))).isTrue();
            mvc.perform(get(collection)).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        }
    }

    @Test
    void publicSecretAndPrivateParentPermissionsAreAppliedForBothContentTypes() throws Exception {
        for (String parent : new String[]{createPost(owner, "PUBLIC"), project(owner, "PUBLIC")}) {
            String collection = comments(parent);
            String publicComment = createComment(collection, writer, "public", "PUBLIC");
            String secretComment = createComment(collection, writer, "private note", "SECRET");
            mvc.perform(get(collection)).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content[0].id").value(publicComment));
            mvc.perform(get(collection).header(HttpHeaders.AUTHORIZATION, bearer(writer)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2));
            mvc.perform(get(collection).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2));
            mvc.perform(get(collection).header(HttpHeaders.AUTHORIZATION, bearer(stranger)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
            mvc.perform(get(collection + "/" + secretComment)).andExpect(status().isNotFound());
            mvc.perform(get(collection + "/" + secretComment).header(HttpHeaders.AUTHORIZATION, bearer(writer)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.visibility").value("SECRET"));
            mvc.perform(get(collection + "/" + secretComment).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(secretComment));
            mvc.perform(get(collection + "/" + publicComment).header(HttpHeaders.AUTHORIZATION, bearer(stranger)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void ownerCanListAndReadSecretCommentsOnHiddenOrBlockedParentsButForeignUsersCannot() throws Exception {
        for (String parent : new String[]{createPost(owner, "PUBLIC"), project(owner, "PUBLIC")}) {
            String collection = comments(parent);
            String secret = createComment(collection, writer, "secret", "SECRET");
            jdbc.update("update " + contentTable(parent) + " set visibility_status='HIDDEN', is_blocked=true, blocked_at=current_timestamp, "
                    + "blocked_by_admin_id=1 where id=?", Long.parseLong(parent));
            em.clear();
            mvc.perform(get(collection)).andExpect(status().isNotFound());
            mvc.perform(get(collection).header(HttpHeaders.AUTHORIZATION, bearer(stranger))).andExpect(status().isNotFound());
            mvc.perform(get(collection).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
            mvc.perform(get(collection + "/" + secret).header(HttpHeaders.AUTHORIZATION, bearer(writer)))
                    .andExpect(status().isOk());
            mvc.perform(get(collection + "/" + secret)).andExpect(status().isNotFound());
        }
    }

    @Test
    void commenterCanStillEditAndDeleteWhenParentIsHiddenOrBlocked() throws Exception {
        for (String parent : new String[]{createPost(owner, "PUBLIC"), project(owner, "PUBLIC")}) {
            String collection = comments(parent);
            String comment = createComment(collection, writer, "initial", "PUBLIC");
            jdbc.update("update " + contentTable(parent) + " set visibility_status='HIDDEN' where id=?", Long.parseLong(parent));
            em.clear();
            String item = collection + "/" + comment;
            mvc.perform(patch(item).header(HttpHeaders.AUTHORIZATION, bearer(writer)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"body\":\"changed while hidden\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.body").value("changed while hidden"));
            jdbc.update("update " + contentTable(parent) + " set visibility_status='HIDDEN', is_blocked=true, blocked_at=current_timestamp, "
                    + "blocked_by_admin_id=1 where id=?", Long.parseLong(parent));
            em.clear();
            mvc.perform(delete(item).header(HttpHeaders.AUTHORIZATION, bearer(writer))).andExpect(status().isNoContent());
            mvc.perform(get(item).header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isNotFound());
        }
    }

    @Test
    void foreignWritersCannotChangeCommentsAndIncompleteActiveUsersCanCreate() throws Exception {
        Member incomplete = members.saveAndFlush(new Member("incomplete-" + suffix(), null, MemberStatus.ACTIVE, null, null));
        for (String parent : new String[]{createPost(owner, "PUBLIC"), project(owner, "PUBLIC")}) {
            String collection = comments(parent);
            String comment = createComment(collection, incomplete, "profile not required", "PUBLIC");
            String item = collection + "/" + comment;
            mvc.perform(patch(item).header(HttpHeaders.AUTHORIZATION, bearer(stranger)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"body\":\"stolen\"}"))
                    .andExpect(status().isNotFound());
            mvc.perform(delete(item).header(HttpHeaders.AUTHORIZATION, bearer(stranger))).andExpect(status().isNotFound());
            mvc.perform(get(item).header(HttpHeaders.AUTHORIZATION, bearer(incomplete)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.author.id").value(incomplete.getId().toString()));
        }
    }

    @Test
    void validatesBodyVisibilityUnknownFieldsAndPaginationQuery() throws Exception {
        String collection = comments(createPost(owner, "PUBLIC"));
        for (String payload : List.of("{}", "null", "[]", "{\"body\":null,\"visibility\":\"PUBLIC\"}",
                "{\"body\":\"  \",\"visibility\":\"PUBLIC\"}", "{\"body\":\"x\",\"visibility\":null}",
                "{\"body\":\"x\",\"visibility\":\"HIDDEN\"}", "{\"body\":\"x\",\"visibility\":\"PUBLIC\",\"other\":true}",
                "{\"body\":\"" + "x".repeat(2001) + "\",\"visibility\":\"PUBLIC\"}")) {
            mvc.perform(post(collection).header(HttpHeaders.AUTHORIZATION, bearer(writer)).contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(status().isBadRequest());
        }
        String comment = createComment(collection, writer, "valid", "PUBLIC");
        for (String payload : List.of("{\"visibility\":\"OTHER\"}", "{\"body\":null}", "{\"other\":1}")) {
            mvc.perform(patch(collection + "/" + comment).header(HttpHeaders.AUTHORIZATION, bearer(writer))
                            .contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(patch(collection + "/" + comment).header(HttpHeaders.AUTHORIZATION, bearer(writer))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        for (var request : List.of(get(collection).param("unknown", "x"), get(collection).param("size", "101"),
                get(collection).param("page", "-1"), get(collection).param("sort", "id,asc"),
                get(collection).param("size", "10").param("size", "20"))) {
            mvc.perform(request).andExpect(status().isBadRequest());
        }
        mvc.perform(post(collection).contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"guest\",\"visibility\":\"PUBLIC\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listUsesStableCreatedAtOrderingAndSupportsUpdatedAtSortAndCors() throws Exception {
        String collection = comments(createPost(owner, "PUBLIC"));
        String first = createComment(collection, writer, "first", "PUBLIC");
        String second = createComment(collection, stranger, "second", "PUBLIC");
        em.flush();
        jdbc.update("update post_comment set created_at='2026-01-01T00:00:00Z', updated_at='2026-01-01T00:00:00Z' where id in (?,?)",
                Long.parseLong(first), Long.parseLong(second));
        em.clear();
        String ascendingId = List.of(first, second).stream().sorted((a, b) -> Long.compare(Long.parseLong(a), Long.parseLong(b))).findFirst().orElseThrow();
        mvc.perform(get(collection).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2)).andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.content[0].id").value(ascendingId));
        mvc.perform(get(collection).param("page", "1").param("size", "1").param("sort", "updatedAt,desc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.hasPrevious").value(true));
        mvc.perform(options(collection).header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN));
    }

    @Test
    void deletedAndWithdrawalParentsHideCommentsWhileSuspendedPublicAuthorsRemainVisible() throws Exception {
        String deletedPost = createPost(owner, "PUBLIC");
        String deletedComment = createComment(comments(deletedPost), writer, "deleted parent", "PUBLIC");
        em.flush();
        jdbc.update("update post set visibility_status='DELETED', deleted_at=current_timestamp where id=?", Long.parseLong(deletedPost));
        em.clear();
        mvc.perform(get(comments(deletedPost))).andExpect(status().isNotFound());
        mvc.perform(get(comments(deletedPost) + "/" + deletedComment)).andExpect(status().isNotFound());

        String publicPost = createPost(writer, "PUBLIC");
        String publicComment = createComment(comments(publicPost), writer, "still here", "PUBLIC");
        em.flush();
        jdbc.update("update member set status='SUSPENDED' where id=?", writer.getId());
        em.clear();
        mvc.perform(get(comments(publicPost))).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(publicComment));
    }

    @Test
    void withdrawalCommentAuthorsAndParentOwnersAreHidden() throws Exception {
        String post = createPost(owner, "PUBLIC");
        String project = project(owner, "PUBLIC");
        java.util.Map<String, String> ids = new java.util.HashMap<>();
        for (String parent : new String[]{post, project}) {
            ids.put(parent, createComment(comments(parent), writer, "withdrawn secret", "SECRET"));
            createComment(comments(parent), writer, "withdrawn public", "PUBLIC");
            createComment(comments(parent), stranger, "remaining", "PUBLIC");
        }
        jdbc.update("update member set status='WITHDRAWAL_PENDING', withdrawal_requested_at=now(), withdrawal_scheduled_at=now()+interval '7 days' where id=?", writer.getId());
        em.clear();
        for (String parent : new String[]{post, project}) {
            mvc.perform(get(comments(parent)).header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(1)).andExpect(jsonPath("$.data.content[0].body").value("remaining"));
            mvc.perform(get(comments(parent) + "/" + ids.get(parent)).header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isNotFound());
            mvc.perform(post(comments(parent)).header(HttpHeaders.AUTHORIZATION, bearer(writer))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"denied\",\"visibility\":\"PUBLIC\"}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCOUNT_WITHDRAWAL_PENDING"));
        }
        jdbc.update("update member set status='WITHDRAWAL_PENDING', withdrawal_requested_at=now(), withdrawal_scheduled_at=now()+interval '7 days' where id=?", owner.getId());
        em.clear();
        for (String parent : new String[]{post, project}) {
            mvc.perform(get(comments(parent))).andExpect(status().isNotFound());
            mvc.perform(get(comments(parent) + "/" + ids.get(parent)).header(HttpHeaders.AUTHORIZATION, bearer(writer))).andExpect(status().isNotFound());
        }
    }

    @Test
    void unicodeLimitsAndWrongParentIdsCannotExposeCommentBodies() throws Exception {
        String parent = createPost(owner, "PUBLIC");
        String otherParent = createPost(owner, "PUBLIC");
        String collection = comments(parent);
        String id = createComment(collection, writer, "😀".repeat(2000), "SECRET");
        mvc.perform(get(comments(otherParent) + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isNotFound());
        mvc.perform(patch(comments(otherParent) + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(writer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"denied\"}")).andExpect(status().isNotFound());
        for (String invalid : new String[]{"😀".repeat(2001), "\u0000"}) {
            String body = mapper.createObjectNode().put("body", invalid).put("visibility", "PUBLIC").toString();
            mvc.perform(post(collection).header(HttpHeaders.AUTHORIZATION, bearer(writer)).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        // UTF-8 변환이 대체 문자로 바꾸지 않도록 잘못된 surrogate를 JSON escape로 전달한다.
        mvc.perform(post(collection).header(HttpHeaders.AUTHORIZATION, bearer(writer)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"\\uD800\",\"visibility\":\"PUBLIC\"}")).andExpect(status().isBadRequest());
        jdbc.update("update member set status='SUSPENDED' where id=?", writer.getId());
        em.clear();
        mvc.perform(get(collection).header(HttpHeaders.AUTHORIZATION, bearer(writer))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(get(collection + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(writer))).andExpect(status().isForbidden());
        mvc.perform(delete(collection + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(writer))).andExpect(status().isForbidden());
    }

    private String createPost(Member author, String visibility) throws Exception {
        String json = "{\"title\":\"comment parent\",\"summary\":\"summary\",\"visibilityStatus\":\"" + visibility
                + "\",\"blocks\":[{\"type\":\"TEXT\",\"content\":\"body\"}]}";
        return createdId(mvc.perform(post(POSTS).header(HttpHeaders.AUTHORIZATION, bearer(author)).contentType(MediaType.APPLICATION_JSON)
                .content(json)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private String project(Member author, String visibility) throws Exception {
        String json = "{\"name\":\"comment project\",\"lifecycleStatus\":\"IN_PROGRESS\",\"visibilityStatus\":\"" + visibility
                + "\",\"tagIds\":[],\"features\":[],\"links\":[]}";
        return createdId(mvc.perform(post(PROJECTS).header(HttpHeaders.AUTHORIZATION, bearer(author)).contentType(MediaType.APPLICATION_JSON)
                .content(json)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private String comments(String parentId) {
        return (jdbc.queryForObject("select count(*) from post where id=?", Long.class, Long.parseLong(parentId)) == 1
                ? POSTS : PROJECTS) + "/" + parentId + "/comments";
    }

    private String createComment(String collection, Member author, String body, String visibility) throws Exception {
        String json = mapper.createObjectNode().put("body", body).put("visibility", visibility).toString();
        var result = mvc.perform(post(collection).header(HttpHeaders.AUTHORIZATION, bearer(author))
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isCreated());
        JsonNode response = mapper.readTree(result.andReturn().getResponse().getContentAsString());
        String id = response.at("/data/id").asText();
        org.assertj.core.api.Assertions.assertThat(result.andReturn().getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo(collection + "/" + id);
        return id;
    }

    private String createdId(String json) throws Exception { return mapper.readTree(json).at("/data/id").asText(); }
    private String table(String collectionParentId) { return comments(collectionParentId).startsWith(POSTS) ? "post_comment" : "project_comment"; }
    private String contentTable(String parentId) { return comments(parentId).startsWith(POSTS) ? "post" : "project"; }
    private Member initializedMember(String label) {
        Member created = members.saveAndFlush(new Member(label + "-" + suffix(), null, MemberStatus.ACTIVE, null, null));
        return profiles.complete(created.getId(), "blog-" + created.getId(), "author-" + created.getId(), null);
    }
    private String suffix() { return UUID.randomUUID().toString().substring(0, 8); }
    private String bearer(Member member) { return "Bearer " + tokens.issueForMember(member.getId(), Instant.now()); }
}
