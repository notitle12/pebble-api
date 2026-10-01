package com.pebble.api.project.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.auth.infrastructure.naver.NaverOAuthGateway;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProjectDiscoveryIntegrationTest extends AuthenticationTestSupport {
    private static final String PROJECTS = "/api/v1/projects";
    private static final String ORIGIN = "http://localhost:3000";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MemberRepository members;
    @Autowired MemberProfileService profiles;
    @Autowired AccessTokenService tokens;
    @Autowired TagRepository tags;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockitoBean NaverOAuthGateway naver;
    Member owner;
    Member other;

    @BeforeEach
    void setup() {
        owner = initializedMember("discovery-owner");
        other = initializedMember("discovery-other");
    }

    @Test
    void searchMatchesNameSummaryDescriptionAndTagAndDeduplicatesProjects() throws Exception {
        Tag tag = tag("needle-tag", TagStatus.ACTIVE);
        Tag secondTag = tag("needle-second", TagStatus.ACTIVE);
        String nameMatch = create(owner, "needle-name", "ordinary", "ordinary", List.of());
        String summaryMatch = create(owner, "ordinary summary project", "needle-summary", "ordinary", List.of());
        String descriptionMatch = create(owner, "ordinary description project", "ordinary", "needle-description", List.of());
        String tagMatch = create(owner, "ordinary tagged project", "ordinary", "ordinary", List.of(tag));
        String allMatch = create(owner, "needle-multiple", "needle-multiple", "needle-multiple", List.of(tag, secondTag));

        assertSearch("needle", nameMatch, summaryMatch, descriptionMatch, tagMatch, allMatch);
        mvc.perform(search("needle").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(5))
                .andExpect(jsonPath("$.data.content[0].features").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].description").doesNotExist());
        mvc.perform(search("needle").param("tagId", tag.getId().toString()).param("lifecycleStatus", "IN_PROGRESS").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2)).andExpect(jsonPath("$.data.content.length()").value(1));
        mvc.perform(search("needle").param("lifecycleStatus", "COMPLETED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void searchTreatsWildcardsAsLiteralAndStripsCaseInsensitiveUnicodeQuery() throws Exception {
        String percent = create(owner, "literal % marker", null, null, List.of());
        String underscore = create(owner, "literal _ marker", null, null, List.of());
        String backslash = create(owner, "literal \\ marker", null, null, List.of());
        String sqlLooking = create(owner, "x' OR 1=1 -- marker", null, null, List.of());
        String unicode = create(owner, "A😀mazing Project", null, null, List.of());
        String longUnicode = create(owner, "unicode query project", null, "😀".repeat(200), List.of());

        assertSearch("%", percent);
        assertSearch("_", underscore);
        assertSearch("\\", backslash);
        assertSearch("x' OR 1=1 --", sqlLooking);
        assertSearch("  😀MAZING PROJECT  ", unicode);
        assertSearch("😀".repeat(200), longUnicode);
    }

    @Test
    void globalListFiltersTagAndLifecycleAndPaginatesWithStableTieOrder() throws Exception {
        Tag selected = tag("selected", TagStatus.ACTIVE);
        Tag otherTag = tag("other", TagStatus.ACTIVE);
        String first = create(owner, "first", null, null, List.of(selected), "IN_PROGRESS");
        String second = create(owner, "second", null, null, List.of(selected), "IN_PROGRESS");
        create(owner, "completed", null, null, List.of(selected), "COMPLETED");
        create(owner, "other tag", null, null, List.of(otherTag), "IN_PROGRESS");
        em.flush();
        jdbc.update("update project set published_at='2026-01-01T00:00:00Z' where id in (?,?)",
                Long.parseLong(first), Long.parseLong(second));
        em.clear();
        List<String> sortedIds = List.of(first, second).stream()
                .sorted(Comparator.<String>comparingLong(Long::parseLong).reversed()).toList();

        mvc.perform(get(PROJECTS).param("tagId", selected.getId().toString()).param("lifecycleStatus", "IN_PROGRESS").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2)).andExpect(jsonPath("$.data.content[0].id").value(sortedIds.get(0)))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mvc.perform(get(PROJECTS).param("tagId", selected.getId().toString()).param("lifecycleStatus", "IN_PROGRESS")
                        .param("page", "1").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].id").value(sortedIds.get(1)))
                .andExpect(jsonPath("$.data.hasPrevious").value(true));
        mvc.perform(get(PROJECTS).param("lifecycleStatus", "COMPLETED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get(PROJECTS).param("tagId", otherTag.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get(PROJECTS).param("sort", "createdAt,asc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(0));
        mvc.perform(get("/api/v1/members/" + owner.getId() + "/projects")
                        .param("tagId", selected.getId().toString()).param("lifecycleStatus", "IN_PROGRESS").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].id").value(sortedIds.get(0)));
        mvc.perform(get("/api/v1/members/" + owner.getId() + "/projects").param("tagId", otherTag.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void searchAndPublicListsFilterVisibilityBlockAndWithdrawalBeforeCounting() throws Exception {
        String visible = create(owner, "filter needle visible", null, null, List.of());
        String hidden = create(owner, "HIDDEN", "filter needle hidden", null, null, List.of());
        String blocked = create(owner, "filter needle blocked", null, null, List.of());
        create(other, "filter needle withdrawal", null, null, List.of());
        Member suspended = initializedMember("suspended-owner");
        String suspendedProject = create(suspended, "filter needle suspended", null, null, List.of());
        em.flush();
        jdbc.update("update project set is_blocked=true, blocked_at=current_timestamp, blocked_by_admin_id=1 where id=?",
                Long.parseLong(blocked));
        jdbc.update("update member set status='WITHDRAWAL_PENDING', withdrawal_requested_at=current_timestamp, "
                + "withdrawal_scheduled_at=current_timestamp + interval '7 days' where id=?", other.getId());
        jdbc.update("update member set status='SUSPENDED' where id=?", suspended.getId());
        em.clear();

        mvc.perform(search("filter needle")).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[*].id", org.hamcrest.Matchers.containsInAnyOrder(visible, suspendedProject)));
        mvc.perform(get(PROJECTS).param("lifecycleStatus", "IN_PROGRESS"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2));
        mvc.perform(get("/api/v1/members/" + owner.getId() + "/projects"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(visible));
        mvc.perform(get("/api/v1/members/" + other.getId() + "/projects")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/members/999999999999999999/projects")).andExpect(status().isNotFound());
        mvc.perform(search("filter needle").param("visibilityStatus", "HIDDEN")).andExpect(status().isBadRequest());
        mvc.perform(get(PROJECTS + "/" + hidden).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.visibilityStatus").value("HIDDEN"));
        mvc.perform(get(PROJECTS + "/" + blocked).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.isBlocked").value(true));
    }

    @Test
    void ownProjectListRequiresActiveUserFiltersVisibilityAndIncludesBlockedProjects() throws Exception {
        String visible = create(owner, "PUBLIC", "own visible", null, null, List.of());
        String hidden = create(owner, "HIDDEN", "own hidden", null, null, List.of());
        String blocked = create(owner, "PUBLIC", "own blocked", null, null, List.of());
        String deleted = create(owner, "PUBLIC", "own deleted", null, null, List.of());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(PROJECTS + "/" + deleted)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isNoContent());
        em.flush();
        jdbc.update("update project set is_blocked=true, blocked_at=current_timestamp, blocked_by_admin_id=1 where id=?",
                Long.parseLong(blocked));
        em.clear();

        mvc.perform(get("/api/v1/members/me/projects").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content[*].id", org.hamcrest.Matchers.containsInAnyOrder(visible, hidden, blocked)))
                .andExpect(jsonPath("$.data.content[?(@.id == '" + blocked + "')].isBlocked", org.hamcrest.Matchers.contains(true)));
        mvc.perform(get("/api/v1/members/me/projects").header(HttpHeaders.AUTHORIZATION, bearer(owner)).param("visibilityStatus", "PUBLIC"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[*].id", org.hamcrest.Matchers.containsInAnyOrder(visible, blocked)));
        mvc.perform(get("/api/v1/members/me/projects").header(HttpHeaders.AUTHORIZATION, bearer(owner)).param("visibilityStatus", "HIDDEN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(hidden));
        mvc.perform(get("/api/v1/members/me/projects").header(HttpHeaders.AUTHORIZATION, bearer(owner)).param("visibilityStatus", "DELETED"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/members/me/projects")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/members/me/projects").header(HttpHeaders.AUTHORIZATION, "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        jdbc.update("update member set status='SUSPENDED' where id=?", owner.getId());
        em.clear();
        mvc.perform(get("/api/v1/members/me/projects").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isForbidden());
    }

    @Test
    void validatesSearchListAndMemberPathParameters() throws Exception {
        Tag tag = tag("validation", TagStatus.ACTIVE);
        for (MockHttpServletRequestBuilder request : List.of(
                get("/api/v1/projects/search"), search(""), search("  "), search("x".repeat(201)), search("\u0000"),
                search("one").param("q", "two"), search("one").param("unknown", "x"),
                search("one").param("tagId", "0"), search("one").param("tagId", "9223372036854775808"),
                search("one").param("lifecycleStatus", "DONE"), search("one").param("page", "-1"),
                search("one").param("size", "101"), search("one").param("sort", "name,asc"),
                search("one").param("sort", "publishedAt"), get(PROJECTS).param("tagId", "abc"),
                get(PROJECTS).param("lifecycleStatus", "DONE"), get(PROJECTS).param("tagId", tag.getId().toString()).param("tagId", "1"))) {
            mvc.perform(request).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/members/999999999999999999/projects")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/members/01/projects")).andExpect(status().isBadRequest());
    }

    @Test
    void projectSearchAndMemberDiscoveryAllowGuestCorsGet() throws Exception {
        mvc.perform(options("/api/v1/projects/search").header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN));
        mvc.perform(options("/api/v1/members/123/projects").header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN));
        mvc.perform(options("/api/v1/members/me/projects").header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isOk());
        mvc.perform(options("/api/v1/projects/search").header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }

    private void assertSearch(String q, String... expectedIds) throws Exception {
        mvc.perform(search(q)).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(expectedIds.length))
                .andExpect(jsonPath("$.data.content[*].id", org.hamcrest.Matchers.containsInAnyOrder(expectedIds)));
    }

    private MockHttpServletRequestBuilder search(String q) {
        return get(PROJECTS + "/search").param("q", q);
    }

    private String create(Member owner, String name, String summary, String description, List<Tag> selected) throws Exception {
        return create(owner, "PUBLIC", name, summary, description, selected);
    }

    private String create(Member owner, String visibility, String name, String summary, String description,
                          List<Tag> selected) throws Exception {
        return create(owner, visibility, name, summary, description, selected, "IN_PROGRESS");
    }

    private String create(Member owner, String name, String summary, String description, List<Tag> selected,
                          String lifecycle) throws Exception {
        return create(owner, "PUBLIC", name, summary, description, selected, lifecycle);
    }

    private String create(Member owner, String visibility, String name, String summary, String description,
                          List<Tag> selected, String lifecycle) throws Exception {
        ObjectNode json = mapper.createObjectNode().put("name", name).put("lifecycleStatus", lifecycle)
                .put("visibilityStatus", visibility);
        if (summary != null) json.put("summary", summary);
        if (description != null) json.put("description", description);
        ArrayNode tagIds = json.putArray("tagIds");
        selected.forEach(tag -> tagIds.add(tag.getId().toString()));
        json.putArray("features");
        json.putArray("links");
        ResultActions result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(PROJECTS)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner)).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(json)))
                .andExpect(status().isCreated());
        return response(result).at("/data/id").asText();
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
