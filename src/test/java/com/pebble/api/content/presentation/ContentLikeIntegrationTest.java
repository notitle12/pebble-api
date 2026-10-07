package com.pebble.api.content.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ContentLikeIntegrationTest extends AuthenticationTestSupport {
    private static final String POSTS = "/api/v1/posts";
    private static final String PROJECTS = "/api/v1/projects";
    private static final String ORIGIN = "http://localhost:3000";

    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired MemberProfileService profiles;
    @Autowired AccessTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockitoBean(name = "naverOAuthClient") OAuthProviderClient naver;
    Member author;
    Member liker;
    Member anotherLiker;

    @BeforeEach
    void setup() {
        author = member("author");
        liker = member("liker");
        anotherLiker = member("another");
    }

    @Test
    void postLikesAreIdempotentRecreatableAndDecorateDetailAndLists() throws Exception {
        String postId = createPost("PUBLIC");
        String path = POSTS + "/" + postId + "/like";

        // Bearer 없는 쓰기는 기존 CSRF 방어에서 먼저 거부된다.
        mvc.perform(put(path)).andExpect(status().isForbidden());
        mvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, bearer(liker)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, bearer(liker))).andExpect(status().isNoContent());
        long firstLikeId = jdbc.queryForObject("select id from post_like where post_id=? and member_id=?", Long.class,
                Long.parseLong(postId), liker.getId());
        mvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, bearer(liker))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from post_like where post_id=?", Long.class, Long.parseLong(postId))).isEqualTo(1);
        mvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, bearer(anotherLiker))).andExpect(status().isNoContent());
        mvc.perform(get(POSTS + "/" + postId).header(HttpHeaders.AUTHORIZATION, bearer(liker)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.likeCount").value(2)).andExpect(jsonPath("$.data.likedByMe").value(true));
        mvc.perform(get(POSTS).header(HttpHeaders.AUTHORIZATION, bearer(liker)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].likeCount").value(2))
                .andExpect(jsonPath("$.data.content[0].likedByMe").value(true));
        mvc.perform(get(POSTS)).andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].likedByMe").value(false));

        mvc.perform(delete(path).header(HttpHeaders.AUTHORIZATION, bearer(liker))).andExpect(status().isNoContent());
        mvc.perform(delete(path).header(HttpHeaders.AUTHORIZATION, bearer(liker))).andExpect(status().isNoContent());
        mvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, bearer(liker))).andExpect(status().isNoContent());
        long replacementLikeId = jdbc.queryForObject("select id from post_like where post_id=? and member_id=? and deleted_at is null", Long.class,
                Long.parseLong(postId), liker.getId());
        assertThat(replacementLikeId).isNotEqualTo(firstLikeId);
    }

    @Test
    void projectLikesAreIdempotentAndDecoratedInSearchAndPublicDetail() throws Exception {
        String projectId = createProject("PUBLIC");
        String path = PROJECTS + "/" + projectId + "/like";
        mvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, bearer(liker))).andExpect(status().isNoContent());
        mvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, bearer(liker))).andExpect(status().isNoContent());
        mvc.perform(get(PROJECTS + "/" + projectId).header(HttpHeaders.AUTHORIZATION, bearer(liker)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.likeCount").value(1))
                .andExpect(jsonPath("$.data.likedByMe").value(true));
        mvc.perform(get(PROJECTS + "/search").param("q", "likeable").header(HttpHeaders.AUTHORIZATION, bearer(liker)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].likeCount").value(1))
                .andExpect(jsonPath("$.data.content[0].likedByMe").value(true));
        mvc.perform(get(PROJECTS)).andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].likedByMe").value(false));
        mvc.perform(delete(path).header(HttpHeaders.AUTHORIZATION, bearer(liker))).andExpect(status().isNoContent());
        mvc.perform(delete(path).header(HttpHeaders.AUTHORIZATION, bearer(liker))).andExpect(status().isNoContent());
        mvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, bearer(liker))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from project_like where project_id=?", Long.class,
                Long.parseLong(projectId))).isEqualTo(2);
    }

    @Test
    void onlyPublicUnblockedContentCanBeLikedAndPrivateOwnerSummariesStayEmpty() throws Exception {
        String hiddenPost = createPost("HIDDEN");
        String publicPost = createPost("PUBLIC");
        String blockedPost = createPost("PUBLIC");
        String hiddenProject = createProject("HIDDEN");
        String blockedProject = createProject("PUBLIC");
        em.flush();
        jdbc.update("update post set is_blocked=true, blocked_at=current_timestamp, blocked_by_admin_id=1 where id=?",
                Long.parseLong(blockedPost));
        jdbc.update("update project set is_blocked=true, blocked_at=current_timestamp, blocked_by_admin_id=1 where id=?",
                Long.parseLong(blockedProject));
        em.clear();

        for (String endpoint : new String[]{POSTS + "/" + hiddenPost + "/like", POSTS + "/" + blockedPost + "/like",
                PROJECTS + "/" + hiddenProject + "/like", PROJECTS + "/" + blockedProject + "/like"}) {
            mvc.perform(put(endpoint).header(HttpHeaders.AUTHORIZATION, bearer(liker))).andExpect(status().isNotFound());
        }
        mvc.perform(get(POSTS + "/" + hiddenPost).header(HttpHeaders.AUTHORIZATION, bearer(author)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.likeCount").value(0)).andExpect(jsonPath("$.data.likedByMe").value(false));
        mvc.perform(get(PROJECTS + "/" + blockedProject).header(HttpHeaders.AUTHORIZATION, bearer(author)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.likeCount").value(0)).andExpect(jsonPath("$.data.likedByMe").value(false));
        mvc.perform(put(POSTS + "/" + publicPost + "/like").header(HttpHeaders.AUTHORIZATION, bearer(liker)))
                .andExpect(status().isNoContent());
        mvc.perform(put(PROJECTS + "/" + createProject("PUBLIC") + "/like").header(HttpHeaders.AUTHORIZATION, bearer(liker)))
                .andExpect(status().isNoContent());
    }

    @Test
    void suspendedLikersRemainCountedAndWithdrawalLikersAreExcluded() throws Exception {
        String postId = createPost("PUBLIC");
        mvc.perform(put(POSTS + "/" + postId + "/like").header(HttpHeaders.AUTHORIZATION, bearer(liker))).andExpect(status().isNoContent());
        mvc.perform(put(POSTS + "/" + postId + "/like").header(HttpHeaders.AUTHORIZATION, bearer(anotherLiker))).andExpect(status().isNoContent());
        em.flush();
        jdbc.update("update member set status='SUSPENDED' where id=?", liker.getId());
        jdbc.update("update member set status='WITHDRAWAL_PENDING', withdrawal_requested_at=current_timestamp, "
                + "withdrawal_scheduled_at=current_timestamp + interval '7 days' where id=?", anotherLiker.getId());
        em.clear();
        mvc.perform(get(POSTS + "/" + postId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.likeCount").value(1)).andExpect(jsonPath("$.data.likedByMe").value(false));
        mvc.perform(put(POSTS + "/" + postId + "/like").header(HttpHeaders.AUTHORIZATION, bearer(anotherLiker)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCOUNT_WITHDRAWAL_PENDING"));
    }

    @Test
    void corsAllowsLikeWritesAndMalformedIdsAreRejected() throws Exception {
        mvc.perform(options(POSTS + "/123/like").header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN));
        mvc.perform(put(POSTS + "/0/like").header(HttpHeaders.AUTHORIZATION, bearer(liker))).andExpect(status().isBadRequest());
        mvc.perform(put(POSTS + "/999999999999999999999999/like").header(HttpHeaders.AUTHORIZATION, bearer(liker)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void inactiveActorsCannotWriteAndProfileCompletionIsNotRequired() throws Exception {
        Member unconfigured = members.saveAndFlush(new Member("unconfigured-" + UUID.randomUUID().toString().substring(0, 8),
                null, MemberStatus.ACTIVE, null, null));
        String post = createPost("PUBLIC");
        String project = createProject("PUBLIC");
        for (String path : new String[]{POSTS + "/" + post + "/like", PROJECTS + "/" + project + "/like"}) {
            mvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, bearer(unconfigured))).andExpect(status().isNoContent());
        }
        jdbc.update("update member set status='SUSPENDED' where id=?", unconfigured.getId());
        em.clear();
        for (String path : new String[]{POSTS + "/" + post + "/like", PROJECTS + "/" + project + "/like"}) {
            mvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, bearer(unconfigured))).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ACCOUNT_SUSPENDED"));
            mvc.perform(delete(path).header(HttpHeaders.AUTHORIZATION, bearer(unconfigured))).andExpect(status().isForbidden());
        }
        jdbc.update("update member set status='WITHDRAWAL_PENDING', withdrawal_requested_at=now(), withdrawal_scheduled_at=now()+interval '7 days' where id=?",
                unconfigured.getId());
        em.clear();
        for (String path : new String[]{POSTS + "/" + post + "/like", PROJECTS + "/" + project + "/like"}) {
            mvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, bearer(unconfigured))).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ACCOUNT_WITHDRAWAL_PENDING"));
            mvc.perform(delete(path).header(HttpHeaders.AUTHORIZATION, bearer(unconfigured))).andExpect(status().isForbidden());
        }
    }

    @Test
    void hiddenAndDeletedTargetsRejectCancellationAndPreserveLikesUntilPublicAgain() throws Exception {
        String post = createPost("PUBLIC");
        String project = createProject("PUBLIC");
        for (String path : new String[]{POSTS + "/" + post, PROJECTS + "/" + project}) {
            mvc.perform(put(path + "/like").header(HttpHeaders.AUTHORIZATION, bearer(liker))).andExpect(status().isNoContent());
            mvc.perform(patch(path).header(HttpHeaders.AUTHORIZATION, bearer(author))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"visibilityStatus\":\"HIDDEN\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.likeCount").value(0));
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(author))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.likeCount").value(0)).andExpect(jsonPath("$.data.likedByMe").value(false));
            mvc.perform(delete(path + "/like").header(HttpHeaders.AUTHORIZATION, bearer(liker))).andExpect(status().isNotFound());
            mvc.perform(patch(path).header(HttpHeaders.AUTHORIZATION, bearer(author))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"visibilityStatus\":\"PUBLIC\"}")).andExpect(status().isOk());
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(liker))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.likeCount").value(1)).andExpect(jsonPath("$.data.likedByMe").value(true));
            mvc.perform(delete(path).header(HttpHeaders.AUTHORIZATION, bearer(author))).andExpect(status().isNoContent());
            mvc.perform(put(path + "/like").header(HttpHeaders.AUTHORIZATION, bearer(liker))).andExpect(status().isNotFound());
            mvc.perform(delete(path + "/like").header(HttpHeaders.AUTHORIZATION, bearer(liker))).andExpect(status().isNotFound());
        }
    }

    @Test
    void rejectsRequestBodiesQueriesAndWithdrawalTargetOwner() throws Exception {
        String post = createPost("PUBLIC");
        String project = createProject("PUBLIC");
        for (String path : new String[]{POSTS + "/" + post + "/like", PROJECTS + "/" + project + "/like"}) {
            mvc.perform(put(path).param("memberId", liker.getId().toString()).header(HttpHeaders.AUTHORIZATION, bearer(liker)))
                    .andExpect(status().isBadRequest());
            mvc.perform(delete(path).param("unknown", "x").header(HttpHeaders.AUTHORIZATION, bearer(liker)))
                    .andExpect(status().isBadRequest());
            mvc.perform(delete(path).header(HttpHeaders.AUTHORIZATION, bearer(liker))
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        }
        jdbc.update("update member set status='WITHDRAWAL_PENDING', withdrawal_requested_at=now(), withdrawal_scheduled_at=now()+interval '7 days' where id=?",
                author.getId());
        em.clear();
        for (String path : new String[]{POSTS + "/" + post + "/like", PROJECTS + "/" + project + "/like"}) {
            mvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, bearer(liker))).andExpect(status().isNotFound());
            mvc.perform(delete(path).header(HttpHeaders.AUTHORIZATION, bearer(liker))).andExpect(status().isNotFound());
        }
    }

    private String createPost(String visibility) throws Exception {
        String json = "{\"title\":\"Like post\",\"summary\":\"summary\",\"visibilityStatus\":\"" + visibility
                + "\",\"blocks\":[{\"type\":\"TEXT\",\"content\":\"body\"}]}";
        var result = mvc.perform(post(POSTS).header(HttpHeaders.AUTHORIZATION, bearer(author))
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isCreated());
        return objectMapper().readTree(result.andReturn().getResponse().getContentAsString()).at("/data/id").asText();
    }

    private String createProject(String visibility) throws Exception {
        String json = "{\"name\":\"Likeable Project\",\"lifecycleStatus\":\"IN_PROGRESS\",\"visibilityStatus\":\""
                + visibility + "\"}";
        var result = mvc.perform(post(PROJECTS).header(HttpHeaders.AUTHORIZATION, bearer(author))
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isCreated());
        return objectMapper().readTree(result.andReturn().getResponse().getContentAsString()).at("/data/id").asText();
    }

    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper() { return mapper; }

    private Member member(String label) {
        Member created = members.saveAndFlush(new Member(label + "-" + UUID.randomUUID().toString().substring(0, 8),
                null, MemberStatus.ACTIVE, null, null));
        return profiles.complete(created.getId(), "blog-" + created.getId(), "author-" + created.getId(), null);
    }

    private String bearer(Member member) { return "Bearer " + tokens.issueForMember(member.getId(), Instant.now()); }
}
