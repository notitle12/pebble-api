package com.pebble.api.post.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
import java.util.List;
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
class PostSeriesIntegrationTest extends AuthenticationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MemberRepository members;
    @Autowired MemberProfileService profiles;
    @Autowired AccessTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockitoBean(name = "naverOAuthClient") OAuthProviderClient naver;
    Member owner, other;

    @BeforeEach void setup() {
        owner = member(); other = member();
    }

    @Test void createsEditsListsAndIsolatesSeriesOwnership() throws Exception {
        String id = createSeries(owner, "AI 사용법");
        mvc.perform(get("/api/v1/members/me/series").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(id))
                .andExpect(jsonPath("$.data[0].postCount").value(0));
        mvc.perform(patch("/api/v1/series/" + id).header(HttpHeaders.AUTHORIZATION, bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"기초부터 차근차근\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("AI 사용법"))
                .andExpect(jsonPath("$.data.description").value("기초부터 차근차근"));
        mvc.perform(get("/api/v1/series/" + id).header(HttpHeaders.AUTHORIZATION, bearer(other))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/series/" + id).header(HttpHeaders.AUTHORIZATION, bearer(other))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/series/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/series/" + id).header(HttpHeaders.AUTHORIZATION, bearer(owner)).param("sort", "name")).andExpect(status().isBadRequest());
    }

    @Test void validatesSeriesTextAndRejectsUnknownOrMalformedFields() throws Exception {
        for (String invalid : new String[]{"{}", "null", "[]", "{\"name\":null}", "{\"name\":1}",
                "{\"name\":\" \"}", "{\"name\":\"x\",\"description\":null}", "{\"name\":\"x\",\"unknown\":true}",
                "{\"name\":\"\\u0000\"}", "{\"name\":\"\\ud800\"}"}) {
            seriesWrite(owner, invalid).andExpect(status().isBadRequest());
        }
        seriesWrite(owner, mapper.writeValueAsString(java.util.Map.of("name", "😀".repeat(100), "description", "a".repeat(1000))))
                .andExpect(status().isCreated());
        seriesWrite(owner, mapper.writeValueAsString(java.util.Map.of("name", "a".repeat(101)))).andExpect(status().isBadRequest());
        seriesWrite(owner, mapper.writeValueAsString(java.util.Map.of("name", "x", "description", "a".repeat(1001)))).andExpect(status().isBadRequest());
    }

    @Test void postSavePreservesOmissionAndNullDetachesOnlySeries() throws Exception {
        String series = createSeries(owner, "ordered");
        String first = createPost(owner, "first", "PUBLIC", series, false);
        String second = createPost(owner, "second", "PUBLIC", series, false);
        patchPost(owner, first, "{\"summary\":\"updated\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.seriesId").value(series));
        patchPost(owner, first, "{\"seriesId\":null}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.seriesId").isEmpty()).andExpect(jsonPath("$.data.title").value("first"))
                .andExpect(jsonPath("$.data.blocks[0].content").value("original text"))
                .andExpect(jsonPath("$.data.tags[0]").value("keep"));
        mvc.perform(get("/api/v1/posts/" + second).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(jsonPath("$.data.seriesDisplayOrder").value(0));
        assertThat(jdbc.queryForObject("select count(*) from post_series where id=?", Long.class, Long.parseLong(series))).isEqualTo(1L);
    }

    @Test void invalidSeriesMakesContentUpdateAtomicAndCrossOwnerAssignmentsFail() throws Exception {
        String mine = createSeries(owner, "mine");
        String theirs = createSeries(other, "theirs");
        String postId = createPost(owner, "unchanged", "PUBLIC", mine, false);
        patchPost(owner, postId, "{\"seriesId\":\"" + theirs + "\",\"title\":\"bad update\"}")
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/posts/" + postId).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(jsonPath("$.data.title").value("unchanged")).andExpect(jsonPath("$.data.seriesId").value(mine));
        mvc.perform(get("/api/v1/series/" + mine + "/posts").header(HttpHeaders.AUTHORIZATION, bearer(other))).andExpect(status().isNotFound());
        for (String bad : new String[]{"{\"seriesId\":1}", "{\"seriesId\":\"0\"}", "{\"seriesDisplayOrder\":null}",
                "{\"seriesDisplayOrder\":-1}", "{\"seriesDisplayOrder\":1.5}", "{\"seriesDisplayOrder\":2147483648}",
                "{\"seriesId\":null,\"seriesDisplayOrder\":0}"}) patchPost(owner, postId, bad).andExpect(status().isBadRequest());
    }

    @Test void publicListCountsAndPaginatesOnlyReadablePostsWithoutLeakingPrivateSequence() throws Exception {
        String series = createSeries(owner, "public");
        String first = createPost(owner, "one", "PUBLIC", series, false);
        createPost(owner, "secret", "HIDDEN", series, false);
        createPost(owner, "draft", "HIDDEN", series, true);
        String blocked = createPost(owner, "blocked", "PUBLIC", series, false);
        String deleted = createPost(owner, "deleted", "PUBLIC", series, false);
        String second = createPost(owner, "two", "PUBLIC", series, false);
        jdbc.update("update post set is_blocked=true,blocked_at=now(),blocked_by_admin_id=1 where id=?", Long.parseLong(blocked));
        mvc.perform(delete("/api/v1/posts/" + deleted).header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isNoContent());
        em.clear();
        String prefix = "/api/v1/blogs/" + owner.getHandle() + "/series";
        mvc.perform(get(prefix)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].postCount").value(2));
        mvc.perform(get(prefix + "/" + series + "/posts").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].id").value(first)).andExpect(jsonPath("$.data.content[0].seriesDisplayOrder").doesNotExist());
        mvc.perform(get(prefix + "/" + series + "/posts").param("size", "1").param("page", "1"))
                .andExpect(jsonPath("$.data.content[0].id").value(second));
        mvc.perform(get("/api/v1/series/" + series + "/posts").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(jsonPath("$.data.totalElements").value(5));
        mvc.perform(get(prefix + "/" + series + "/posts").param("page", "0").param("page", "1")).andExpect(status().isBadRequest());
        mvc.perform(get(prefix + "/" + series + "/posts").param("sort", "createdAt,desc")).andExpect(status().isBadRequest());
    }

    @Test void privateOnlyOrEmptySeriesIsNotPublicEvenForAnAuthenticatedOwner() throws Exception {
        String hidden = createSeries(owner, "private series title");
        createPost(owner, "private", "HIDDEN", hidden, false);
        createSeries(owner, "empty");
        String prefix = "/api/v1/blogs/" + owner.getHandle() + "/series";
        mvc.perform(get(prefix)).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get(prefix + "/" + hidden).header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isNotFound());
        mvc.perform(get(prefix + "/" + hidden + "/posts")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/blogs/" + other.getHandle() + "/series/" + hidden)).andExpect(status().isNotFound());
    }

    @Test void movingPostsKeepsOneSeriesAndIndependentOrdering() throws Exception {
        String a = createSeries(owner, "a"), b = createSeries(owner, "b");
        String first = createPost(owner, "one", "PUBLIC", a, false);
        String second = createPost(owner, "two", "PUBLIC", a, false);
        String third = createPost(owner, "three", "PUBLIC", a, false);
        patchPost(owner, third, "{\"seriesDisplayOrder\":0}").andExpect(status().isOk());
        mvc.perform(get("/api/v1/series/" + a + "/posts").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(jsonPath("$.data.content[0].id").value(third)).andExpect(jsonPath("$.data.content[1].id").value(first));
        patchPost(owner, second, "{\"seriesId\":\"" + b + "\"}").andExpect(status().isOk());
        mvc.perform(get("/api/v1/series/" + a + "/posts").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(jsonPath("$.data.totalElements").value(2)).andExpect(jsonPath("$.data.content[1].seriesDisplayOrder").value(1));
        patchPost(owner, third, "{\"seriesDisplayOrder\":2}").andExpect(status().isBadRequest());
    }

    @Test void deletingSeriesDetachesAllIncludingDeletedPostsAndPreservesTheirContent() throws Exception {
        String series = createSeries(owner, "delete container");
        String live = createPost(owner, "keep", "PUBLIC", series, false);
        String deleted = createPost(owner, "already deleted", "PUBLIC", series, false);
        mvc.perform(delete("/api/v1/posts/" + deleted).header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/series/" + series).header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isNoContent());
        em.clear();
        mvc.perform(get("/api/v1/posts/" + live).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value("keep"))
                .andExpect(jsonPath("$.data.seriesId").isEmpty()).andExpect(jsonPath("$.data.tags[0]").value("keep"))
                .andExpect(jsonPath("$.data.blocks[0].content").value("original text"));
        assertThat(jdbc.queryForObject("select series_id is null from post where id=?", Boolean.class, Long.parseLong(deleted))).isTrue();
        assertThat(jdbc.queryForObject("select visibility_status from post where id=?", String.class, Long.parseLong(deleted))).isEqualTo("DELETED");
    }

    @Test void enforcesSeriesOwnerInDatabaseToo() throws Exception {
        String series = createSeries(other, "other");
        String id = createPost(owner, "own post", "PUBLIC", null, false);
        em.flush();
        assertThatThrownBy(() -> jdbc.update("update post set series_id=? where id=?", Long.parseLong(series), Long.parseLong(id)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void seriesOrderPersistsAndPublicListsFollowItWithoutExposingPrivatePositions() throws Exception {
        String a = createSeries(owner, "first"), hidden = createSeries(owner, "private"), b = createSeries(owner, "last");
        String firstPost = createPost(owner, "public one", "PUBLIC", a, false);
        createPost(owner, "private one", "HIDDEN", hidden, false);
        createPost(owner, "public two", "PUBLIC", b, false);
        mvc.perform(get("/api/v1/members/me/series").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(jsonPath("$.data[0].id").value(b)).andExpect(jsonPath("$.data[2].id").value(a));
        seriesPatch(owner, a, "{\"displayOrder\":0}").andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("first"));
        mvc.perform(get("/api/v1/members/me/series").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(jsonPath("$.data[0].id").value(a)).andExpect(jsonPath("$.data[1].id").value(b));
        mvc.perform(get("/api/v1/blogs/" + owner.getHandle() + "/series"))
                .andExpect(jsonPath("$.data.length()").value(2)).andExpect(jsonPath("$.data[0].id").value(a))
                .andExpect(jsonPath("$.data[1].id").value(b)).andExpect(jsonPath("$.data[0].displayOrder").doesNotExist());
        assertThat(jdbc.queryForObject("select series_id from post where id=?", Long.class, Long.parseLong(firstPost))).isEqualTo(Long.parseLong(a));
        seriesPatch(owner, a, "{\"description\":\"edited without moving\"}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select display_order from post_series where id=?", Integer.class, Long.parseLong(a))).isZero();
        mvc.perform(delete("/api/v1/series/" + b).header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForList("select display_order from post_series where owner_member_id=? order by display_order", Integer.class, owner.getId())).containsExactly(0, 1);
    }

    @Test void seriesOrderRejectsBadRanksAndOtherOwnersAndRollsBackMixedUpdates() throws Exception {
        String a = createSeries(owner, "unchanged"), b = createSeries(owner, "second");
        for (String value : new String[]{"null", "-1", "1.5", "2147483648", "\"0\"", "true", "2"})
            seriesPatch(owner, a, "{\"displayOrder\":" + value + "}").andExpect(status().isBadRequest());
        seriesPatch(other, a, "{\"displayOrder\":0}").andExpect(status().isNotFound());
        seriesPatch(owner, a, "{\"displayOrder\":2,\"name\":\"bad\"}").andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/series/" + a).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(jsonPath("$.data.name").value("unchanged"));
        seriesWrite(owner, "{\"name\":\"invalid create\",\"displayOrder\":0}").andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/series/" + b).contentType(MediaType.APPLICATION_JSON).content("{\"displayOrder\":0}"))
                .andExpect(status().isForbidden()); // unauthenticated writes fail the existing CSRF gate
        mvc.perform(patch("/api/v1/series/" + b).header(HttpHeaders.AUTHORIZATION, "Bearer invalid")
                .contentType(MediaType.APPLICATION_JSON).content("{\"displayOrder\":0}"))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions seriesPatch(Member member, String id, String body) throws Exception {
        return mvc.perform(patch("/api/v1/series/" + id).header(HttpHeaders.AUTHORIZATION, bearer(member)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test void oneRequestAtomicallySavesBothOrdersAndKeepsPostContentAndGlobalOrder() throws Exception {
        String a = createSeries(owner, "a"), b = createSeries(owner, "b");
        String first = createPost(owner, "first", "PUBLIC", a, false), second = createPost(owner, "second", "PUBLIC", a, false), draft = createPost(owner, "draft", "HIDDEN", a, true);
        int globalOrder = jdbc.queryForObject("select display_order from post where id=?", Integer.class, Long.parseLong(first));
        saveOrders(owner, List.of(b,a), List.of(a,b), a, List.of(first,second,draft), List.of(draft,second,first)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/members/me/series").header(HttpHeaders.AUTHORIZATION,bearer(owner))).andExpect(jsonPath("$.data[0].id").value(a));
        mvc.perform(get("/api/v1/series/"+a+"/posts").header(HttpHeaders.AUTHORIZATION,bearer(owner)))
                .andExpect(jsonPath("$.data.content[0].id").value(draft)).andExpect(jsonPath("$.data.content[1].id").value(second)).andExpect(jsonPath("$.data.content[2].id").value(first));
        mvc.perform(get("/api/v1/blogs/"+owner.getHandle()+"/series/"+a+"/posts"))
                .andExpect(jsonPath("$.data.totalElements").value(2)).andExpect(jsonPath("$.data.content[0].id").value(second));
        mvc.perform(get("/api/v1/posts/"+first).header(HttpHeaders.AUTHORIZATION,bearer(owner)))
                .andExpect(jsonPath("$.data.blocks[0].content").value("original text")).andExpect(jsonPath("$.data.tags[0]").value("keep"));
        assertThat(jdbc.queryForObject("select display_order from post where id=?",Integer.class,Long.parseLong(first))).isEqualTo(globalOrder);
    }

    @Test void stalePartRejectsBothOrdersWithoutChangingTheOtherPart() throws Exception {
        String a=createSeries(owner,"a"),b=createSeries(owner,"b"),first=createPost(owner,"one","PUBLIC",a,false),second=createPost(owner,"two","PUBLIC",a,false);
        saveOrders(owner,List.of(b,a),List.of(a,b),a,List.of(second,first),List.of(first,second))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("POST_ORDER_CONFLICT"));
        assertThat(jdbc.queryForList("select id::text from post_series where owner_member_id=? order by display_order",String.class,owner.getId())).containsExactly(b,a);
        saveOrders(owner,List.of(a,b),List.of(b,a),a,List.of(first,second),List.of(second,first))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SERIES_ORDER_CONFLICT"));
        assertThat(jdbc.queryForList("select id::text from post where series_id=? order by series_display_order",String.class,Long.parseLong(a))).containsExactly(first,second);
        patchPost(owner,second,"{\"seriesId\":null}").andExpect(status().isOk());
        saveOrders(owner,null,null,a,List.of(first,second),List.of(second,first)).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select series_id from post where id=?",Long.class,Long.parseLong(second))).isNull();
    }

    @Test void batchOrderValidatesPayloadAndOwnershipAndRejectsUnsupportedQueries() throws Exception {
        String a=createSeries(owner,"a"),post=createPost(owner,"one","PUBLIC",a,false);
        for(String body:new String[]{"{}","null","{\"extra\":1}","{\"seriesOrder\":null}","{\"seriesOrder\":{\"expectedIds\":[1],\"ids\":[1]}}","{\"seriesOrder\":{\"expectedIds\":[\"1\",\"1\"],\"ids\":[\"1\",\"1\"]}}","{\"postOrder\":{\"seriesId\":null,\"expectedIds\":[],\"ids\":[]}}"})
            mvc.perform(put("/api/v1/members/me/series/order").header(HttpHeaders.AUTHORIZATION,bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        saveOrders(owner,List.of(a),List.of("1"),null,null,null).andExpect(status().isBadRequest());
        saveOrders(other,null,null,a,List.of(post),List.of(post)).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/members/me/series/order").param("sort","name").header(HttpHeaders.AUTHORIZATION,bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"seriesOrder\":{\"expectedIds\":[],\"ids\":[]}}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/members/me/series/order").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }

    @Test void batchCanSaveOnlyOneScopeAndAcceptsAnEmptySeries() throws Exception {
        String a=createSeries(owner,"a"),b=createSeries(owner,"b");
        saveOrders(owner,List.of(b,a),List.of(a,b),null,null,null).andExpect(status().isNoContent());
        saveOrders(owner,null,null,a,List.of(),List.of()).andExpect(status().isNoContent());
        assertThat(jdbc.queryForList("select id::text from post_series where owner_member_id=? order by display_order",String.class,owner.getId())).containsExactly(a,b);
    }

    private ResultActions saveOrders(Member member,List<String> expectedSeries,List<String> series,String id,List<String> expectedPosts,List<String> posts) throws Exception {
        var body=mapper.createObjectNode();
        if(expectedSeries!=null)body.set("seriesOrder",mapper.valueToTree(java.util.Map.of("expectedIds",expectedSeries,"ids",series)));
        if(expectedPosts!=null)body.set("postOrder",mapper.valueToTree(java.util.Map.of("seriesId",id,"expectedIds",expectedPosts,"ids",posts)));
        return mvc.perform(put("/api/v1/members/me/series/order").header(HttpHeaders.AUTHORIZATION,bearer(member)).contentType(MediaType.APPLICATION_JSON).content(body.toString()));
    }

    private String createSeries(Member member, String name) throws Exception {
        return json(seriesWrite(member, mapper.writeValueAsString(java.util.Map.of("name", name)))
                .andExpect(status().isCreated())).at("/data/id").asText();
    }
    private ResultActions seriesWrite(Member member, String body) throws Exception {
        return mvc.perform(post("/api/v1/series").header(HttpHeaders.AUTHORIZATION, bearer(member)).contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private String createPost(Member member, String title, String visibility, String series, boolean draft) throws Exception {
        var body = mapper.createObjectNode().put("title", title).put("summary", "keep summary").put("visibilityStatus", visibility).put("draft", draft);
        body.putArray("blocks").addObject().put("type", "TEXT").put("content", "original text");
        body.putArray("tags").add("keep");
        if (series != null) body.put("seriesId", series);
        return json(mvc.perform(post("/api/v1/posts").header(HttpHeaders.AUTHORIZATION, bearer(member))
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isCreated())).at("/data/id").asText();
    }
    private ResultActions patchPost(Member member, String id, String body) throws Exception {
        return mvc.perform(patch("/api/v1/posts/" + id).header(HttpHeaders.AUTHORIZATION, bearer(member)).contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private JsonNode json(ResultActions result) throws Exception { return mapper.readTree(result.andReturn().getResponse().getContentAsString()); }
    private Member member() {
        Member created = members.saveAndFlush(agreedMember(new Member("series-" + UUID.randomUUID().toString().substring(0, 8), null, MemberStatus.ACTIVE, null, null)));
        return profiles.complete(created.getId(), "blog-" + created.getId(), "author-" + created.getId(), null);
    }
    private String bearer(Member member) { return "Bearer " + tokens.issueForMember(member.getId(), Instant.now()); }
}
