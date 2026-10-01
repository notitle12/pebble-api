package com.pebble.api.post.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.auth.infrastructure.naver.NaverOAuthGateway;
import com.pebble.api.category.domain.Category;
import com.pebble.api.category.domain.CategoryStatus;
import com.pebble.api.category.infrastructure.persistence.CategoryRepository;
import com.pebble.api.member.application.MemberProfileService;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import com.pebble.api.tag.domain.Tag;
import com.pebble.api.tag.domain.TagStatus;
import com.pebble.api.tag.infrastructure.persistence.TagRepository;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
class PostIntegrationTest extends AuthenticationTestSupport {
    private static final String PATH = "/api/v1/posts";
    private static final String ORIGIN = "http://localhost:3000";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MemberRepository members;
    @Autowired com.pebble.api.post.infrastructure.persistence.PostRepository postRepository;
    @Autowired MemberProfileService profiles;
    @Autowired CategoryRepository categories;
    @Autowired TagRepository tags;
    @Autowired AccessTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockitoBean NaverOAuthGateway naver;
    Member owner;
    Member other;

    @BeforeEach
    void setup() {
        owner = initializedMember("owner");
        other = initializedMember("other");
    }

    @Test
    void createsStructuredPostWithStringIdsAndOwnerOnlyMetadata() throws Exception {
        Category category = category(null, CategoryStatus.ACTIVE);
        Tag tag = tag(TagStatus.ACTIVE);
        String id = create(owner, "PUBLIC", category, List.of(tag));
        mvc.perform(get(PATH + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.author.id").value(owner.getId().toString()))
                .andExpect(jsonPath("$.data.blocks[0].type").value("TEXT"))
                .andExpect(jsonPath("$.data.blocks[1].language").value("JAVA"))
                .andExpect(jsonPath("$.data.blocks[1].displayOrder").value(1))
                .andExpect(jsonPath("$.data.category.id").value(category.getId().toString()))
                .andExpect(jsonPath("$.data.tags[0].id").value(tag.getId().toString()))
                .andExpect(jsonPath("$.data.publishedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.isBlocked").doesNotExist())
                .andExpect(jsonPath("$.data.blockedByAdminId").doesNotExist());
        mvc.perform(get(PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(jsonPath("$.data.isBlocked").value(false));
    }

    @Test
    void patchDistinguishesOmittedNullAndReplacementArrays() throws Exception {
        Category category = category(null, CategoryStatus.ACTIVE);
        Tag tag = tag(TagStatus.ACTIVE);
        String id = create(owner, "PUBLIC", category, List.of(tag));
        change(id, "{\"title\":\"changed\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary").value("summary"))
                .andExpect(jsonPath("$.data.blocks.length()").value(2));
        change(id, "{\"summary\":null,\"categoryId\":null,\"tagIds\":[],\"blocks\":[{\"type\":\"TEXT\",\"content\":\"new\"}]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary").isEmpty())
                .andExpect(jsonPath("$.data.category").isEmpty()).andExpect(jsonPath("$.data.tags").isEmpty())
                .andExpect(jsonPath("$.data.blocks.length()").value(1))
                .andExpect(jsonPath("$.data.blocks[0].content").value("new"));
        assertThat(jdbc.queryForObject("select count(*) from post_block where post_id = ?", Long.class, Long.parseLong(id))).isEqualTo(1);
    }

    @Test
    void recordsFirstPublicationOnlyAndDoesNotPublishHiddenPostsToGuests() throws Exception {
        String id = create(owner, "HIDDEN", null, List.of());
        mvc.perform(get(PATH + "/" + id)).andExpect(status().isNotFound());
        mvc.perform(get(PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(other))).andExpect(status().isNotFound());
        JsonNode published = response(change(id, "{\"visibilityStatus\":\"PUBLIC\"}").andExpect(status().isOk()));
        String first = published.at("/data/publishedAt").asText();
        change(id, "{\"visibilityStatus\":\"HIDDEN\"}").andExpect(status().isOk());
        change(id, "{\"visibilityStatus\":\"PUBLIC\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publishedAt").value(first));
    }

    @Test
    void deletesLogicallyAndRejectsResurrectionAndOtherOwners() throws Exception {
        String id = create(owner, "PUBLIC", null, List.of());
        mvc.perform(patch(PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(other))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"stolen\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete(PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(other))).andExpect(status().isNotFound());
        mvc.perform(delete(PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isNoContent())
                .andExpect(content().string(""));
        mvc.perform(get(PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isNotFound());
        change(id, "{\"title\":\"revived\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CONTENT_DELETED"));
        mvc.perform(delete(PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select visibility_status from post where id = ?", String.class, Long.parseLong(id))).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("select deleted_at is not null from post where id = ?", Boolean.class, Long.parseLong(id))).isTrue();
    }

    @Test
    void blockedPostsRemainManageableOnlyByOwnerAndCannotBeUnblockedByInput() throws Exception {
        String id = create(owner, "PUBLIC", null, List.of());
        jdbc.update("update post set is_blocked=true, blocked_at=current_timestamp, blocked_by_admin_id=1 where id=?", Long.parseLong(id));
        em.clear();
        mvc.perform(get(PATH + "/" + id)).andExpect(status().isNotFound());
        mvc.perform(get(PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(other))).andExpect(status().isNotFound());
        mvc.perform(get(PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isBlocked").value(true));
        change(id, "{\"visibilityStatus\":\"PUBLIC\"}").andExpect(status().isOk()).andExpect(jsonPath("$.data.isBlocked").value(true));
        change(id, "{\"isBlocked\":false}").andExpect(status().isBadRequest());
        mvc.perform(get(PATH).param("authorId", owner.getId().toString())).andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(get("/api/v1/members/me/posts").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(jsonPath("$.data.content[0].isBlocked").value(true));
    }

    @Test
    void inactiveAccountsCannotWriteAndWithdrawalPendingContentIsHidden() throws Exception {
        String id = create(owner, "PUBLIC", null, List.of());
        for (String state : List.of("SUSPENDED", "WITHDRAWAL_PENDING")) {
            jdbc.update("update member set status=?, withdrawal_requested_at=case when ?='WITHDRAWAL_PENDING' then current_timestamp end, "
                    + "withdrawal_scheduled_at=case when ?='WITHDRAWAL_PENDING' then current_timestamp + interval '7 days' end where id=?", state, state, state, owner.getId());
            em.clear();
            String expected = state.equals("SUSPENDED") ? "ACCOUNT_SUSPENDED" : "ACCOUNT_WITHDRAWAL_PENDING";
            change(id, "{\"title\":\"denied\"}").andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value(expected));
            mvc.perform(delete(PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isForbidden());
            mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(basic("PUBLIC")))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/members/me/posts").header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isForbidden());
        }
        mvc.perform(get(PATH + "/" + id)).andExpect(status().isNotFound());
        mvc.perform(get(PATH).param("authorId", owner.getId().toString())).andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void validatesActualLeafIncludingInactiveChildrenAndNewInactiveSelections() throws Exception {
        Category parent = category(null, CategoryStatus.ACTIVE);
        category(parent, CategoryStatus.INACTIVE);
        Category inactive = category(null, CategoryStatus.INACTIVE);
        for (Category invalid : List.of(parent, inactive)) {
            mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                            .content(withClassification(basic("PUBLIC"), invalid, List.of())))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_CATEGORY_SELECTION"));
        }
        Tag inactiveTag = tag(TagStatus.INACTIVE);
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(withClassification(basic("PUBLIC"), null, List.of(inactiveTag))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INACTIVE_TAG"));
    }

    @Test
    void preservesExistingInactiveReferencesAndPublicTaxonomyDiscovery() throws Exception {
        Category parent = category(null, CategoryStatus.ACTIVE);
        Category leaf = category(parent, CategoryStatus.ACTIVE);
        Tag tag = tag(TagStatus.ACTIVE);
        String id = create(owner, "PUBLIC", leaf, List.of(tag));
        jdbc.update("update category set status='INACTIVE' where id in (?,?)", leaf.getId(), parent.getId());
        jdbc.update("update tag set status='INACTIVE' where id=?", tag.getId());
        em.clear();
        change(id, "{\"categoryId\":\"" + leaf.getId() + "\",\"tagIds\":[\"" + tag.getId() + "\"]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.category.status").value("INACTIVE"))
                .andExpect(jsonPath("$.data.tags[0].status").value("INACTIVE"));
        JsonNode tree = response(mvc.perform(get("/api/v1/categories")).andExpect(status().isOk()));
        assertThat(tree.at("/data").toString()).contains(leaf.getId().toString(), parent.getId().toString());
        JsonNode vocabulary = response(mvc.perform(get("/api/v1/tags")).andExpect(status().isOk()));
        assertThat(vocabulary.at("/data").toString()).contains(tag.getId().toString());
        mvc.perform(get(PATH).param("categoryId", leaf.getId().toString()).param("tagId", tag.getId().toString()))
                .andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get(PATH).param("categoryId", parent.getId().toString()).param("tagId", tag.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        change(id, "{\"visibilityStatus\":\"HIDDEN\"}").andExpect(status().isOk());
        assertThat(response(mvc.perform(get("/api/v1/categories"))).at("/data").toString()).doesNotContain(leaf.getId().toString(), parent.getId().toString());
        assertThat(response(mvc.perform(get("/api/v1/tags"))).at("/data").toString()).doesNotContain(tag.getId().toString());
        change(id, "{\"tagIds\":[],\"categoryId\":null}").andExpect(status().isOk());
        change(id, "{\"categoryId\":\"" + leaf.getId() + "\"}").andExpect(status().isConflict());
    }

    @Test
    void missingClassificationRollsBackWholePatch() throws Exception {
        String id = create(owner, "PUBLIC", null, List.of());
        change(id, "{\"title\":\"must not save\",\"tagIds\":[\"9223372036854775807\"]}").andExpect(status().isNotFound());
        mvc.perform(get(PATH + "/" + id)).andExpect(jsonPath("$.data.title").value("title"));
    }

    @Test
    void rejectsInvalidFieldsTypesIdsAndForbiddenStateValues() throws Exception {
        String id = create(owner, "PUBLIC", null, List.of());
        for (String invalid : List.of("{\"title\":null}", "{\"title\":\" \"}", "{\"blocks\":[]}", "{\"blocks\":null}",
                "{\"tagIds\":null}", "{\"tagIds\":[\"1\",\"1\"]}", "{\"categoryId\":1}", "{\"categoryId\":\"0\"}",
                "{\"categoryId\":\"9223372036854775808\"}", "{\"visibilityStatus\":\"DELETED\"}", "{\"visibilityStatus\":null}",
                "{\"boardId\":1}", "{\"projectId\":\"1\"}", "{\"blockedAt\":null}", "{\"blockedByAdminId\":null}",
                "{\"unknown\":true}", "{\"blocks\":[{\"type\":\"CODE\",\"content\":\"code\"}]}",
                "{\"blocks\":[{\"type\":\"CODE\",\"language\":\"RUST\",\"content\":\"code\"}]}",
                "{\"blocks\":[{\"type\":\"TEXT\",\"content\":\"text\",\"id\":\"1\"}]}")) {
            change(id, invalid).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.traceId").isNotEmpty());
        }
        for (String invalid : List.of("{}", "[]", "null", "{\"title\":\"title\",\"blocks\":[]}")) {
            mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void enforcesPostgresCodePointLimitsAndReturnsFieldDetails() throws Exception {
        String id = create(owner, "PUBLIC", null, List.of());
        change(id, mapper.writeValueAsString(Map.of("title", "😀".repeat(200), "summary", "😀".repeat(500))))
                .andExpect(status().isOk());
        change(id, mapper.writeValueAsString(Map.of("title", "😀".repeat(201))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details[0].field").value("title"));
        change(id, mapper.writeValueAsString(Map.of("summary", "a".repeat(501)))).andExpect(status().isBadRequest());
        change(id, mapper.writeValueAsString(Map.of("blocks", List.of(Map.of("type", "TEXT", "content", "😀".repeat(50000))))))
                .andExpect(status().isOk());
        change(id, mapper.writeValueAsString(Map.of("blocks", List.of(Map.of("type", "TEXT", "content", "a".repeat(50001))))))
                .andExpect(status().isBadRequest());
        change(id, mapper.writeValueAsString(Map.of("blocks", List.of(Map.of("type", "TEXT", "content", "text", "title", "a".repeat(101))))))
                .andExpect(status().isBadRequest());
        change(id, "{\"summary\":\"\\u0000\"}").andExpect(status().isBadRequest());
    }

    @Test
    void filtersBeforePaginationAndSortsTiesByNumericId() throws Exception {
        Category category = category(null, CategoryStatus.ACTIVE);
        Tag tag = tag(TagStatus.ACTIVE);
        String first = create(owner, "PUBLIC", category, List.of(tag));
        String second = create(owner, "PUBLIC", category, List.of(tag));
        create(owner, "HIDDEN", category, List.of(tag));
        create(other, "PUBLIC", null, List.of());
        jdbc.update("update post set published_at='2026-01-01T00:00:00Z' where id in (?,?)", Long.parseLong(first), Long.parseLong(second));
        em.clear();
        String bigger = Long.parseLong(first) > Long.parseLong(second) ? first : second;
        mvc.perform(get(PATH).param("categoryId", category.getId().toString()).param("tagId", tag.getId().toString())
                        .param("authorId", owner.getId().toString()).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].id").value(bigger))
                .andExpect(jsonPath("$.data.content[0].blocks").doesNotExist())
                .andExpect(jsonPath("$.data.totalElements").value(2)).andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mvc.perform(get(PATH).param("authorId", owner.getId().toString()).param("page", "1000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isEmpty());
        mvc.perform(get("/api/v1/members/me/posts").header(HttpHeaders.AUTHORIZATION, bearer(owner)).param("visibilityStatus", "HIDDEN"))
                .andExpect(jsonPath("$.data.totalElements").value(1)).andExpect(jsonPath("$.data.content[0].visibilityStatus").value("HIDDEN"));
    }

    @Test
    void rejectsUnsupportedQueryAndPageValues() throws Exception {
        for (Map<String, String> query : List.of(Map.of("size", "0"), Map.of("size", "101"), Map.of("page", "-1"),
                Map.of("page", "2147483647"), Map.of("size", "foo"), Map.of("sort", "title,asc"), Map.of("sort", "createdAt"),
                Map.of("sort", "createdAt,up"), Map.of("visibilityStatus", "HIDDEN"), Map.of("boardId", "1"), Map.of("categoryId", "-1"))) {
            var request = get(PATH);
            query.forEach(request::param);
            mvc.perform(request).andExpect(status().isBadRequest());
        }
    }

    @Test
    void bearerWritesWorkWithoutCsrfAndCookiesCannotAuthenticateThem() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(basic("PUBLIC")))
                .andExpect(status().isForbidden());
        mvc.perform(post(PATH).cookie(new Cookie("refresh_token", "cookie-only"), new Cookie("JSESSIONID", "session"))
                        .header(HttpHeaders.ORIGIN, ORIGIN).contentType(MediaType.APPLICATION_JSON).content(basic("PUBLIC")))
                .andExpect(status().isForbidden());
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer invalid").contentType(MediaType.APPLICATION_JSON).content(basic("PUBLIC")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/members/me/posts").cookie(new Cookie("refresh_token", "cookie-only"))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/token/refresh").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isForbidden());
    }

    @Test
    void allowsOnlyImplementedCorsPathsAndMethods() throws Exception {
        for (String method : List.of("GET", "PATCH", "DELETE")) {
            mvc.perform(options(PATH + "/123").header(HttpHeaders.ORIGIN, ORIGIN)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method).header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization,Content-Type"))
                    .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN));
        }
        mvc.perform(options(PATH).header(HttpHeaders.ORIGIN, ORIGIN).header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk());
        mvc.perform(options(PATH + "/123").header(HttpHeaders.ORIGIN, "https://evil.example").header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH"))
                .andExpect(status().isForbidden());
        for (String path : List.of(PATH + "/search", PATH + "/123/thumbnail", "/api/v1/admin/posts")) {
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isForbidden());
        }
        mvc.perform(options(PATH + "/123/thumbnail").header(HttpHeaders.ORIGIN, ORIGIN).header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT"))
                .andExpect(status().isForbidden());
    }

    @Test
    void slugSupportsReadableAndNumericUrlsAndHiddenVisibility() throws Exception {
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(basic("PUBLIC"));
        json.put("slug", "Spring-Security");
        ResultActions created = mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(json)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.slug").value("spring-security"))
                .andExpect(jsonPath("$.data.urlKey").value("spring-security"));
        String id = response(created).at("/data/id").asText();
        created.andExpect(header().string(HttpHeaders.LOCATION, blogPath(owner, "spring-security")));
        mvc.perform(get(blogPath(owner, "spring-security"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id));
        mvc.perform(get(PATH + "/" + id)).andExpect(status().isOk());
        change(id, mapper.writeValueAsString(Map.of("slug", "changed"))).andExpect(status().isBadRequest());
        change(id, mapper.writeValueAsString(Map.of("visibilityStatus", "HIDDEN"))).andExpect(status().isOk());
        mvc.perform(get(blogPath(owner, "spring-security"))).andExpect(status().isNotFound());
        mvc.perform(get(blogPath(owner, "spring-security")).header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isOk());
        String fallback = create(owner, "PUBLIC", null, List.of());
        mvc.perform(get(PATH + "/" + fallback)).andExpect(jsonPath("$.data.slug").isEmpty()).andExpect(jsonPath("$.data.urlKey").value("2"));
    }

    @Test
    void rejectsMalformedSlugsAndReservesNumericAndSearchPaths() throws Exception {
        for (String slug : List.of("", "123", "search", "SEARCH", "한글-슬러그", "café", "Keyword", "has space", "../path", "a--b", "-start", "end-", "a".repeat(201))) {
            var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(basic("PUBLIC"));
            json.put("slug", slug);
            mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(json)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void movesPostsWithinAuthorBlogWithoutChangingGlobalFeedOrder() throws Exception {
        String oldest = create(owner, "PUBLIC", null, List.of());
        String middle = create(owner, "HIDDEN", null, List.of());
        String newest = create(owner, "PUBLIC", null, List.of());
        String theirs = create(other, "PUBLIC", null, List.of());
        change(oldest, mapper.writeValueAsString(Map.of("displayOrder", 0))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayOrder").value(0));
        mvc.perform(get("/api/v1/members/" + owner.getId() + "/posts"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].id").value(oldest))
                .andExpect(jsonPath("$.data.content[1].id").value(newest)).andExpect(jsonPath("$.data.totalElements").value(2));
        mvc.perform(get("/api/v1/members/me/posts").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(jsonPath("$.data.content[0].id").value(oldest)).andExpect(jsonPath("$.data.content[1].id").value(newest))
                .andExpect(jsonPath("$.data.content[2].id").value(middle));
        mvc.perform(get(PATH).param("authorId", owner.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].id").value(newest));
        mvc.perform(get(PATH + "/" + theirs)).andExpect(jsonPath("$.data.displayOrder").value(0));
        change(oldest, mapper.writeValueAsString(Map.of("displayOrder", 99))).andExpect(status().isBadRequest());
        for (String bad : List.of("{\"displayOrder\":null}", "{\"displayOrder\":-1}", "{\"displayOrder\":1.5}", "{\"displayOrder\":\"0\"}")) {
            change(oldest, bad).andExpect(status().isBadRequest());
        }
        mvc.perform(delete(PATH + "/" + newest).header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/members/me/posts").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(jsonPath("$.data.content[1].id").value(middle)).andExpect(jsonPath("$.data.content[1].displayOrder").value(1));
        mvc.perform(get(PATH).param("sort", "displayOrder,asc")).andExpect(status().isBadRequest());
    }

    private String create(Member member, String visibility, Category category, List<Tag> selected) throws Exception {
        ResultActions result = mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(member)).contentType(MediaType.APPLICATION_JSON)
                .content(withClassification(basic(visibility), category, selected))).andExpect(status().isCreated());
        String id = response(result).at("/data/id").asText();
        String key = response(result).at("/data/urlKey").asText();
        result.andExpect(header().string(HttpHeaders.LOCATION, blogPath(member, key)));
        return id;
    }
    private String basic(String visibility) {
        return "{\"title\":\"title\",\"summary\":\"summary\",\"visibilityStatus\":\"" + visibility
                + "\",\"blocks\":[{\"type\":\"TEXT\",\"content\":\"text\"},{\"type\":\"CODE\",\"content\":\"code\",\"language\":\"JAVA\",\"title\":\"example\"}]}";
    }
    private String withClassification(String input, Category category, List<Tag> selected) throws Exception {
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(input);
        if (category != null) node.put("categoryId", category.getId().toString());
        var array = node.putArray("tagIds");
        selected.forEach(tag -> array.add(tag.getId().toString()));
        return mapper.writeValueAsString(node);
    }
    private ResultActions change(String id, String json) throws Exception {
        return mvc.perform(patch(PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(json));
    }
    private JsonNode response(ResultActions actions) throws Exception {
        return mapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }
    private String blogPath(Member member, String key) { return "/api/v1/blogs/" + member.getHandle() + "/posts/" + key; }
    private Member initializedMember(String label) {
        Member created = members.saveAndFlush(new Member(label + "-" + java.util.UUID.randomUUID().toString().substring(0,8), null, MemberStatus.ACTIVE, null, null));
        return profiles.complete(created.getId(), "blog-" + created.getId(), "author-" + created.getId(), null);
    }

    @Test
    void slugDuplicatesAreScopedToAuthorAndAutomaticallySuffixedWithoutReuse() throws Exception {
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(basic("PUBLIC"));
        json.put("slug", "same-topic");
        for (String expected : List.of("same-topic", "same-topic-2")) {
            mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(json.toString()))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.data.slug").value(expected));
        }
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(other)).contentType(MediaType.APPLICATION_JSON).content(json.toString()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.slug").value("same-topic"));
        String deletedId = response(mvc.perform(get(blogPath(owner, "same-topic")))).at("/data/id").asText();
        mvc.perform(delete(PATH + "/" + deletedId).header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isNoContent());
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(json.toString()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.slug").value("same-topic-3"))
                .andExpect(jsonPath("$.data.postNumber").value("3"));
        mvc.perform(get(blogPath(owner, "1"))).andExpect(status().isNotFound());
        mvc.perform(get(blogPath(owner, "3"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.slug").value("same-topic-3"));
        mvc.perform(get(blogPath(other, "1"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.author.id").value(other.getId().toString()));
    }

    @Test
    void suffixOnMaximumLengthSlugKeepsSingleHyphenAndValidPublicAddress() throws Exception {
        String base = "a".repeat(197) + "-bc";
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(basic("PUBLIC"));
        json.put("slug", base);
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(json.toString()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.slug").value(base));
        String suffixed = "a".repeat(197) + "-2";
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(json.toString()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.slug").value(suffixed));
        mvc.perform(get(blogPath(owner, suffixed))).andExpect(status().isOk()).andExpect(jsonPath("$.data.slug").value(suffixed));
    }

    @Test
    void publicBlogHandleProvidesManualOrderingAndPendingAccountsCannotCreatePosts() throws Exception {
        Member pending = members.saveAndFlush(new Member("pending-" + java.util.UUID.randomUUID().toString().substring(0,8), null, MemberStatus.ACTIVE, null, null));
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(pending)).contentType(MediaType.APPLICATION_JSON).content(basic("PUBLIC")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PROFILE_REQUIRED"));
        var pendingPost = postRepository.saveAndFlush(new com.pebble.api.post.domain.Post(pending, null, "pending", null,
                com.pebble.api.post.domain.PostVisibility.PUBLIC, null, 1));
        mvc.perform(patch(PATH + "/" + pendingPost.getId()).header(HttpHeaders.AUTHORIZATION, bearer(pending))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PROFILE_REQUIRED"));
        mvc.perform(delete(PATH + "/" + pendingPost.getId()).header(HttpHeaders.AUTHORIZATION, bearer(pending)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PROFILE_REQUIRED"));
        String id = create(owner, "PUBLIC", null, List.of());
        mvc.perform(get("/api/v1/blogs/" + owner.getHandle() + "/posts"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].id").value(id));
        mvc.perform(get("/api/v1/blogs/no-such-author/posts")).andExpect(status().isNotFound());
    }

    private String bearer(Member member) { return "Bearer " + tokens.issueForMember(member.getId(), Instant.now()); }
    private Category category(Category parent, CategoryStatus status) {
        return categories.saveAndFlush(new Category(parent, "topic", "test-" + java.util.UUID.randomUUID(), 100, status));
    }
    private Tag tag(TagStatus status) {
        return tags.saveAndFlush(new Tag("tech", "test-" + java.util.UUID.randomUUID(), 100, status));
    }
}
