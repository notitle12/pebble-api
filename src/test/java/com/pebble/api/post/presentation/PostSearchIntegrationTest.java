package com.pebble.api.post.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PostSearchIntegrationTest extends AuthenticationTestSupport {
    private static final String PATH = "/api/v1/posts/search";
    private static final String ORIGIN = "http://localhost:3000";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MemberRepository members;
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
    void searchesTitleBlockContentBlockTitleCategoryParentAndTagAsOrTargets() throws Exception {
        Category categoryParent = category(null, "Parent Needle phrase", CategoryStatus.ACTIVE);
        Category category = category(categoryParent, "Category unusual", CategoryStatus.ACTIVE);
        Tag tag = tag("Tag unusual", TagStatus.ACTIVE);
        String title = create(owner, "needle phrase in title", "ordinary summary", "ordinary content", "ordinary block", null, List.of(), null);
        String content = create(owner, "plain title", "ordinary summary", "needle phrase in content", "ordinary block", null, List.of(), null);
        String blockTitle = create(owner, "another title", "ordinary summary", "ordinary content", "needle phrase block title", null, List.of(), null);
        String categoryMatch = create(owner, "category post", "ordinary summary", "ordinary content", "ordinary block", category, List.of(), null);
        String tagMatch = create(owner, "tag post", "ordinary summary", "ordinary content", "ordinary block", null, List.of(tag), null);
        em.flush();
        jdbc.update("update category set name='needle phrase child' where id=?", category.getId());
        jdbc.update("update category set name='needle phrase parent' where id=?", categoryParent.getId());
        jdbc.update("update tag set name='needle phrase tag' where id=?", tag.getId());
        em.clear();

        mvc.perform(search("NEEDLE PHRASE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(5))
                .andExpect(jsonPath("$.data.content[*].id").value(org.hamcrest.Matchers.containsInAnyOrder(
                        title, content, blockTitle, categoryMatch, tagMatch)))
                .andExpect(jsonPath("$.data.content[0].blocks").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].isBlocked").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].blockedByAdminId").doesNotExist());
        mvc.perform(search("NEEDLE PHRASE").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(5))
                .andExpect(jsonPath("$.data.content[0].isBlocked").doesNotExist());
        mvc.perform(search("needle phrase").param("categoryId", category.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(categoryMatch));
        mvc.perform(search("needle phrase").param("tagId", tag.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(tagMatch));
        mvc.perform(search("needle phrase").param("authorId", other.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void doesNotDuplicatePostsForMultipleMatchingBlocksOrTagsAndCombinesFiltersWithAnd() throws Exception {
        Category category = category(null, "Filter Category", CategoryStatus.ACTIVE);
        Tag matchingTag = tag("Filter Tag", TagStatus.ACTIVE);
        Tag secondTag = tag("Filter Tag Extra", TagStatus.ACTIVE);
        String match = create(owner, "filter post", "summary", "same needle in content", "same needle in title", category,
                List.of(matchingTag, secondTag), null);
        create(other, "filter post other author", "summary", "same needle", "same needle", category, List.of(matchingTag), null);
        create(owner, "filter post other category", "summary", "same needle", "ordinary", null, List.of(matchingTag), null);
        create(owner, "filter post other tag", "summary", "same needle", "ordinary", category, List.of(), null);

        mvc.perform(search("same needle").param("categoryId", category.getId().toString())
                        .param("tagId", matchingTag.getId().toString()).param("authorId", owner.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content.length()").value(1)).andExpect(jsonPath("$.data.content[0].id").value(match));
        mvc.perform(search("Filter Tag").param("categoryId", category.getId().toString())
                        .param("authorId", owner.getId().toString()).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1)).andExpect(jsonPath("$.data.content[0].id").value(match));
    }

    @Test
    void matchesInactiveTaxonomyStillReferencedByPublicPosts() throws Exception {
        Category parent = category(null, "category", CategoryStatus.ACTIVE);
        Category category = category(parent, "child category", CategoryStatus.ACTIVE);
        Tag tag = tag("old tag", TagStatus.ACTIVE);
        String postId = create(owner, "taxonomy post", "summary", "body", "block", category, List.of(tag), null);
        em.flush();
        jdbc.update("update category set name='inactive category match', status='INACTIVE' where id=?", category.getId());
        jdbc.update("update category set name='inactive parent match', status='INACTIVE' where id=?", parent.getId());
        jdbc.update("update tag set name='inactive tag match', status='INACTIVE' where id=?", tag.getId());
        em.clear();
        for (String q : List.of("inactive category match", "inactive parent match", "inactive tag match")) {
            mvc.perform(search(q)).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content[0].id").value(postId));
        }
    }

    @Test
    void treatsSqlWildcardsBackslashAndSqlLookingInputAsLiteralSubstrings() throws Exception {
        String percent = create(owner, "literal % marker", "summary", "body", "block", null, List.of(), null);
        String underscore = create(owner, "literal _ marker", "summary", "body", "block", null, List.of(), null);
        String slash = create(owner, "literal \\ marker", "summary", "body", "block", null, List.of(), null);
        String sql = create(owner, "x' OR 1=1 -- literal", "summary", "body", "block", null, List.of(), null);
        create(owner, "unrelated", "summary", "body", "block", null, List.of(), null);
        for (var pair : List.of(Map.entry("%", percent), Map.entry("_", underscore), Map.entry("\\", slash),
                Map.entry("x' OR 1=1 --", sql))) {
            mvc.perform(search(pair.getKey())).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content[0].id").value(pair.getValue()));
        }
    }

    @Test
    void trimsSearchesCaseInsensitivelyAndCountsUnicodeCodePoints() throws Exception {
        String id = create(owner, "A😀mazing Search Term", "summary", "body", "block", null, List.of(), null);
        mvc.perform(search("  😀MAZING SEARCH  ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1)).andExpect(jsonPath("$.data.content[0].id").value(id));
        create(owner, "x".repeat(200), "summary", "body", "block", null, List.of(), null);
        mvc.perform(search("x".repeat(200))).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        create(owner, "😀".repeat(200), "summary", "body", "block", null, List.of(), null);
        mvc.perform(search("😀".repeat(200))).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void rejectsMissingMalformedDuplicateAndUnsupportedSearchParameters() throws Exception {
        for (var request : List.of(
                get(PATH), get(PATH).param("q", ""), get(PATH).param("q", "   "),
                get(PATH).param("q", "x".repeat(201)), get(PATH).param("q", "\u0000"),
                get(PATH).param("q", "\uD800"), get(PATH).param("q", "one", "two"),
                get(PATH).param("q", "valid").param("unknown", "1"),
                get(PATH).param("q", "valid").param("categoryId", "0"),
                get(PATH).param("q", "valid").param("tagId", "abc"),
                get(PATH).param("q", "valid").param("authorId", "9223372036854775808"),
                get(PATH).param("q", "valid").param("size", "0"),
                get(PATH).param("q", "valid").param("size", "101"),
                get(PATH).param("q", "valid").param("page", "-1"),
                get(PATH).param("q", "valid").param("sort", "title,asc"),
                get(PATH).param("q", "valid").param("sort", "publishedAt"),
                get(PATH).param("q", "valid").param("sort", "createdAt,up"))) {
            mvc.perform(request).andExpect(status().isBadRequest());
        }
    }

    @Test
    void paginatesAfterPublicVisibilityFilteringAndUsesStableDefaultAndAllowedSorts() throws Exception {
        String older = create(owner, "page needle older", "summary", "body", "block", null, List.of(), null);
        String newer = create(owner, "page needle newer", "summary", "body", "block", null, List.of(), null);
        String hidden = create(owner, "page needle hidden", "summary", "body", "block", null, List.of(), null, "HIDDEN");
        String blocked = create(owner, "page needle blocked", "summary", "body", "block", null, List.of(), null);
        String deleted = create(owner, "page needle deleted", "summary", "body", "block", null, List.of(), null);
        create(other, "page needle withdrawn", "summary", "body", "block", null, List.of(), null);
        Member suspendedMember = initializedMember("suspended");
        String suspended = create(suspendedMember, "page needle suspended", "summary", "body", "block", null, List.of(), null);
        em.flush();
        jdbc.update("update post set published_at='2026-01-01T00:00:00Z' where id in (?,?,?)",
                Long.parseLong(older), Long.parseLong(newer), Long.parseLong(suspended));
        jdbc.update("update post set is_blocked=true, blocked_at=current_timestamp, blocked_by_admin_id=1 where id=?", Long.parseLong(blocked));
        jdbc.update("update member set status='WITHDRAWAL_PENDING', withdrawal_requested_at=current_timestamp, "
                + "withdrawal_scheduled_at=current_timestamp + interval '7 days' where id=?", other.getId());
        jdbc.update("update member set status='SUSPENDED' where id=?", suspendedMember.getId());
        em.clear();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/posts/" + deleted)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isNoContent());
        List<String> sortedIds = List.of(older, newer, suspended).stream()
                .sorted(Comparator.<String>comparingLong(Long::parseLong).reversed()).toList();
        mvc.perform(search("page needle")).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content.length()").value(3))
                .andExpect(jsonPath("$.data.content[*].id").value(org.hamcrest.Matchers.contains(sortedIds.toArray())))
                .andExpect(jsonPath("$.data.content[0].visibilityStatus").value("PUBLIC"));
        mvc.perform(search("page needle").param("page", "1").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content[0].id").value(sortedIds.get(1))).andExpect(jsonPath("$.data.hasPrevious").value(true));
        mvc.perform(search("page needle").param("sort", "createdAt,asc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content.length()").value(3));
        mvc.perform(search("page needle").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content[0].isBlocked").doesNotExist());
    }

    @Test
    void doesNotSearchSummaryOrSlug() throws Exception {
        create(owner, "ordinary title", "distinctive summary token", "plain body", "plain block", null, List.of(), "slug-needle-only");
        mvc.perform(search("distinctive summary token")).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(search("slug-needle-only")).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void searchCorsAllowsGetAuthorizationButNotPost() throws Exception {
        mvc.perform(options(PATH).header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN));
        mvc.perform(options(PATH).header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization,Content-Type"))
                .andExpect(status().isForbidden());
        mvc.perform(options(PATH).header(HttpHeaders.ORIGIN, "https://untrusted.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
        mvc.perform(post(PATH).header(HttpHeaders.ORIGIN, ORIGIN).header(HttpHeaders.AUTHORIZATION, bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    private MockHttpServletRequestBuilder search(String q) {
        return get(PATH).param("q", q);
    }

    private String create(Member author, String title, String summary, String blockContent, String blockTitle,
                          Category category, List<Tag> selected, String slug) throws Exception {
        return create(author, title, summary, blockContent, blockTitle, category, selected, slug, "PUBLIC");
    }

    private String create(Member author, String title, String summary, String blockContent, String blockTitle,
                          Category category, List<Tag> selected, String slug, String visibility) throws Exception {
        ObjectNode json = mapper.createObjectNode();
        json.put("title", title);
        json.put("summary", summary);
        json.put("visibilityStatus", visibility);
        if (slug != null) json.put("slug", slug);
        if (category != null) json.put("categoryId", category.getId().toString());
        ArrayNode tagIds = json.putArray("tagIds");
        selected.forEach(tag -> tagIds.add(tag.getId().toString()));
        json.putArray("blocks")
                .add(mapper.createObjectNode().put("type", "TEXT").put("content", blockContent).put("title", blockTitle))
                .add(mapper.createObjectNode().put("type", "CODE").put("language", "JAVA").put("content", blockContent).put("title", blockTitle));
        ResultActions result = mvc.perform(post("/api/v1/posts").header(HttpHeaders.AUTHORIZATION, bearer(author))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(json)))
                .andExpect(status().isCreated());
        return response(result).at("/data/id").asText();
    }

    private Category category(Category parent, String name, CategoryStatus status) {
        return categories.saveAndFlush(new Category(parent, name, "category-" + UUID.randomUUID(), 0, status));
    }

    private Tag tag(String name, TagStatus status) {
        return tags.saveAndFlush(new Tag(name, "tag-" + UUID.randomUUID(), 0, status));
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
