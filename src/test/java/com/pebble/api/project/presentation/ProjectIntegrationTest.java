package com.pebble.api.project.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.auth.application.oauth.OAuthProviderClient;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProjectIntegrationTest extends AuthenticationTestSupport {
    private static final String PATH = "/api/v1/projects";
    private static final String ORIGIN = "http://localhost:3000";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MemberRepository members;
    @Autowired MemberProfileService profiles;
    @Autowired AccessTokenService tokens;
    @Autowired TagRepository tags;
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
    void createsProjectReturnsLocationAndStringIdsThenLoadsFullDetail() throws Exception {
        Tag tag = tag("Spring", TagStatus.ACTIVE);
        ResultActions created = create(owner, "PUBLIC", "Pebble API", "summary", "description",
                List.of(tag), List.of(feature("OAuth login", "Login with OAuth")),
                List.of(link("GITHUB", "Source", "https://github.com/example/pebble", 0)),
                "IN_PROGRESS", "2026-01-02", null);
        String id = response(created).at("/data/id").asText();
        assertThat(id).matches("[1-9][0-9]{0,18}");
        created.andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, PATH + "/" + id))
                .andExpect(jsonPath("$.data.owner.id").value(owner.getId().toString()))
                .andExpect(jsonPath("$.data.tags[0].id").value(tag.getId().toString()))
                .andExpect(jsonPath("$.data.features[0].title").value("OAuth login"))
                .andExpect(jsonPath("$.data.features[0].displayOrder").value(0))
                .andExpect(jsonPath("$.data.links[0].linkType").value("GITHUB"))
                .andExpect(jsonPath("$.data.links[0].url").value("https://github.com/example/pebble"))
                .andExpect(jsonPath("$.data.lifecycleStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.startedOn").value("2026-01-02"))
                .andExpect(jsonPath("$.data.isBlocked").value(false));
        mvc.perform(get(PATH + "/" + id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.owner.handle").value(owner.getHandle()))
                .andExpect(jsonPath("$.data.description").value("description"))
                .andExpect(jsonPath("$.data.summary").value("summary"))
                .andExpect(jsonPath("$.data.media").isArray()).andExpect(jsonPath("$.data.media").isEmpty())
                .andExpect(jsonPath("$.data.features.length()").value(1))
                .andExpect(jsonPath("$.data.links.length()").value(1));
        mvc.perform(get(PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.isBlocked").value(false));
    }

    @Test
    void patchPreservesOmittedFieldsAppliesNullAndReplacesArrays() throws Exception {
        Tag first = tag("first", TagStatus.ACTIVE);
        Tag second = tag("second", TagStatus.ACTIVE);
        String id = projectId(create(owner, "PUBLIC", "Project", "summary", "description",
                List.of(first), List.of(feature("first feature", "first description")),
                List.of(link("GITHUB", "source", "https://example.com/one", 4)), "COMPLETED", "2025-01-01", "2025-12-31"));

        patch(id, "{\"name\":\"Renamed\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Renamed"))
                .andExpect(jsonPath("$.data.summary").value("summary"))
                .andExpect(jsonPath("$.data.features.length()").value(1))
                .andExpect(jsonPath("$.data.features[0].title").value("first feature"))
                .andExpect(jsonPath("$.data.links[0].displayOrder").value(4))
                .andExpect(jsonPath("$.data.tags[0].id").value(first.getId().toString()));

        patch(id, "{\"summary\":null,\"description\":null,\"startedOn\":null,\"completedOn\":null,"
                        + "\"tagIds\":[\"" + second.getId() + "\"],\"features\":[{\"title\":\"replacement\",\"description\":\"new\"}],\"links\":[]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary").isEmpty())
                .andExpect(jsonPath("$.data.description").doesNotExist()).andExpect(jsonPath("$.data.startedOn").isEmpty())
                .andExpect(jsonPath("$.data.completedOn").isEmpty()).andExpect(jsonPath("$.data.tags.length()").value(1))
                .andExpect(jsonPath("$.data.tags[0].id").value(second.getId().toString()))
                .andExpect(jsonPath("$.data.features.length()").value(1))
                .andExpect(jsonPath("$.data.features[0].title").value("replacement"))
                .andExpect(jsonPath("$.data.links").isEmpty());
        assertThat(jdbc.queryForObject("select count(*) from project_feature where project_id=?", Long.class, Long.parseLong(id))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from project_link where project_id=?", Long.class, Long.parseLong(id))).isZero();
        assertThat(jdbc.queryForObject("select count(*) from project_tag where project_id=?", Long.class, Long.parseLong(id))).isEqualTo(1);
    }

    @Test
    void publicListFiltersByTagLifecycleAndPaginatesUsingStableDefaultOrder() throws Exception {
        Tag selected = tag("selected", TagStatus.ACTIVE);
        Tag otherTag = tag("other", TagStatus.ACTIVE);
        String first = projectId(create(owner, "PUBLIC", "first", null, null, List.of(selected), List.of(), List.of(), "IN_PROGRESS", null, null));
        String second = projectId(create(owner, "PUBLIC", "second", null, null, List.of(selected), List.of(), List.of(), "IN_PROGRESS", null, null));
        create(owner, "PUBLIC", "completed", null, null, List.of(selected), List.of(), List.of(), "COMPLETED", null, null);
        create(owner, "PUBLIC", "different tag", null, null, List.of(otherTag), List.of(), List.of(), "IN_PROGRESS", null, null);
        create(owner, "HIDDEN", "hidden", null, null, List.of(selected), List.of(), List.of(), "IN_PROGRESS", null, null);
        em.flush();
        jdbc.update("update project set published_at='2026-01-01T00:00:00Z' where id in (?,?)", Long.parseLong(first), Long.parseLong(second));
        em.clear();
        List<String> sorted = List.of(first, second).stream().sorted(Comparator.<String>comparingLong(Long::parseLong).reversed()).toList();
        mvc.perform(get(PATH).param("tagId", selected.getId().toString()).param("lifecycleStatus", "IN_PROGRESS").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2)).andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(sorted.get(0)))
                .andExpect(jsonPath("$.data.content[0].description").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].features").doesNotExist())
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mvc.perform(get(PATH).param("tagId", selected.getId().toString()).param("lifecycleStatus", "IN_PROGRESS")
                        .param("page", "1").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].id").value(sorted.get(1)))
                .andExpect(jsonPath("$.data.hasPrevious").value(true));
        mvc.perform(get(PATH).param("lifecycleStatus", "COMPLETED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get(PATH).param("tagId", otherTag.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void publicListExcludesHiddenBlockedAndWithdrawalOwnersWhileOwnerCanReadHiddenAndBlocked() throws Exception {
        String visible = projectId(create(owner, "PUBLIC", "visible", null, null, List.of(), List.of(), List.of(), "IN_PROGRESS", null, null));
        String hidden = projectId(create(owner, "HIDDEN", "hidden", null, null, List.of(), List.of(), List.of(), "IN_PROGRESS", null, null));
        String blocked = projectId(create(owner, "PUBLIC", "blocked", null, null, List.of(), List.of(), List.of(), "IN_PROGRESS", null, null));
        create(other, "PUBLIC", "withdrawal owner", null, null, List.of(), List.of(), List.of(), "IN_PROGRESS", null, null);
        em.flush();
        jdbc.update("update project set is_blocked=true, blocked_at=current_timestamp, blocked_by_admin_id=1 where id=?", Long.parseLong(blocked));
        jdbc.update("update member set status='WITHDRAWAL_PENDING', withdrawal_requested_at=current_timestamp, "
                + "withdrawal_scheduled_at=current_timestamp + interval '7 days' where id=?", other.getId());
        em.clear();

        mvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(visible));
        mvc.perform(get(PATH + "/" + hidden)).andExpect(status().isNotFound());
        mvc.perform(get(PATH + "/" + blocked)).andExpect(status().isNotFound());
        mvc.perform(get(PATH + "/" + hidden).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.visibilityStatus").value("HIDDEN"));
        mvc.perform(get(PATH + "/" + blocked).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.isBlocked").value(true));
    }

    @Test
    void rejectsOtherOwnerWritesAndLogicallyDeletesWithoutAllowingResurrection() throws Exception {
        String id = projectId(create(owner, "PUBLIC", "owned", null, null, List.of(), List.of(), List.of(), "IN_PROGRESS", null, null));
        patchAs(other, id, "{\"name\":\"stolen\"}").andExpect(status().isNotFound());
        mvc.perform(delete(PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(other))).andExpect(status().isNotFound());
        mvc.perform(delete(PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(owner))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select visibility_status from project where id=?", String.class, Long.parseLong(id))).isEqualTo("DELETED");
        mvc.perform(get(PATH + "/" + id)).andExpect(status().isNotFound());
        patch(id, "{\"name\":\"revive\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CONTENT_DELETED"));
        mvc.perform(delete(PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CONTENT_DELETED"));
    }

    @Test
    void rejectsNewInactiveTagAndKeepsExistingInactiveReference() throws Exception {
        Tag tag = tag("existing", TagStatus.ACTIVE);
        String id = projectId(create(owner, "PUBLIC", "with tag", null, null, List.of(tag), List.of(), List.of(), "IN_PROGRESS", null, null));
        em.flush();
        jdbc.update("update tag set status='INACTIVE' where id=?", tag.getId());
        em.clear();
        patch(id, "{\"name\":\"still linked\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tags[0].status").value("INACTIVE"));
        patch(id, "{\"tagIds\":[\"" + tag.getId() + "\"]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tags[0].status").value("INACTIVE"));
        em.clear();
        mvc.perform(get("/api/v1/tags")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", org.hamcrest.Matchers.hasItem(tag.getId().toString())));
        mvc.perform(get(PATH).param("tagId", tag.getId().toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
        patch(id, "{\"visibilityStatus\":\"HIDDEN\"}").andExpect(status().isOk());
        mvc.perform(get("/api/v1/tags")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(tag.getId().toString()))));
        create(owner, "PUBLIC", "new selection", null, null, List.of(tag), List.of(), List.of(), "IN_PROGRESS", null, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INACTIVE_TAG"));
    }

    @Test
    void validatesLengthsDatesEnumsDuplicateTagsNestedObjectsAndUnknownFields() throws Exception {
        Tag tag = tag("validation", TagStatus.ACTIVE);
        create(owner, "PUBLIC", "x".repeat(120), "s".repeat(500), "d".repeat(20_000), List.of(tag),
                List.of(feature("f".repeat(100), "d".repeat(2_000))),
                List.of(link("OTHER", "l".repeat(100), "https://example.com/" + "x".repeat(2_000 - 20), 0)),
                "COMPLETED", "2024-02-29", "2025-03-01").andExpect(status().isCreated());

        for (String bad : List.of(
                "{\"name\":\"" + "x".repeat(121) + "\",\"lifecycleStatus\":\"IN_PROGRESS\",\"visibilityStatus\":\"PUBLIC\"}",
                "{\"name\":\"x\",\"summary\":\"" + "x".repeat(501) + "\",\"lifecycleStatus\":\"IN_PROGRESS\",\"visibilityStatus\":\"PUBLIC\"}",
                "{\"name\":\"x\",\"lifecycleStatus\":\"DONE\",\"visibilityStatus\":\"PUBLIC\"}",
                "{\"name\":\"x\",\"lifecycleStatus\":\"IN_PROGRESS\",\"visibilityStatus\":\"PRIVATE\"}",
                "{\"name\":\"x\",\"lifecycleStatus\":\"IN_PROGRESS\",\"visibilityStatus\":\"PUBLIC\",\"startedOn\":\"2025-02-29\"}",
                "{\"name\":\"x\",\"lifecycleStatus\":\"IN_PROGRESS\",\"visibilityStatus\":\"PUBLIC\",\"unknown\":true}",
                "{\"name\":\"x\",\"lifecycleStatus\":\"IN_PROGRESS\",\"visibilityStatus\":\"PUBLIC\",\"isBlocked\":true}",
                "{\"name\":\"x\",\"lifecycleStatus\":\"IN_PROGRESS\",\"visibilityStatus\":\"PUBLIC\",\"tagIds\":[\"" + tag.getId() + "\",\"" + tag.getId() + "\"]}",
                "{\"name\":\"x\",\"lifecycleStatus\":\"IN_PROGRESS\",\"visibilityStatus\":\"PUBLIC\",\"features\":[{\"title\":\"ok\",\"description\":\"d\",\"other\":1}]}",
                "{\"name\":\"x\",\"lifecycleStatus\":\"IN_PROGRESS\",\"visibilityStatus\":\"PUBLIC\",\"features\":[{\"title\":\"" + "f".repeat(101) + "\",\"description\":\"d\"}]}",
                "{\"name\":\"x\",\"lifecycleStatus\":\"IN_PROGRESS\",\"visibilityStatus\":\"PUBLIC\",\"links\":[{\"linkType\":\"WEB\",\"url\":\"https://example.com\",\"displayOrder\":0}]}",
                "{\"name\":\"x\",\"lifecycleStatus\":\"IN_PROGRESS\",\"visibilityStatus\":\"PUBLIC\",\"links\":[{\"linkType\":\"OTHER\",\"url\":\"https://example.com\",\"displayOrder\":-1}]}",
                "{\"name\":\"x\",\"lifecycleStatus\":\"IN_PROGRESS\",\"visibilityStatus\":\"PUBLIC\",\"links\":[{\"linkType\":\"OTHER\",\"url\":\"https://example.com\",\"displayOrder\":0,\"extra\":true}]}",
                "{\"name\":\"x\",\"lifecycleStatus\":\"IN_PROGRESS\",\"visibilityStatus\":\"PUBLIC\",\"links\":[{\"linkType\":\"OTHER\",\"url\":\"javascript:alert(1)\",\"displayOrder\":0}]}")) {
            createRaw(owner, bad).andExpect(status().isBadRequest());
        }
        patch(projectId(create(owner, "PUBLIC", "target", null, null, List.of(), List.of(), List.of(), "IN_PROGRESS", null, null)),
                "{\"links\":[{\"linkType\":\"OTHER\",\"url\":\"https://example.com\",\"displayOrder\":0,\"label\":\"" + "x".repeat(101) + "\"}]}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void enforcesAuthenticationAndCorsForProjectWritesAndLists() throws Exception {
        String body = "{\"name\":\"Auth project\",\"lifecycleStatus\":\"IN_PROGRESS\",\"visibilityStatus\":\"PUBLIC\"}";
        createRaw(null, body).andExpect(status().isForbidden());
        createRaw(owner, body, "Bearer invalid").andExpect(status().isUnauthorized());
        mvc.perform(options(PATH).header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization,Content-Type"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN));
        mvc.perform(options(PATH).header(HttpHeaders.ORIGIN, "https://untrusted.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
        mvc.perform(get(PATH).param("unknown", "1")).andExpect(status().isBadRequest());
    }

    @Test
    void reordersRetainedTagsAndPersistsRepeatedReplacementWithoutLosingLinks() throws Exception {
        Tag first = tag("first", TagStatus.ACTIVE);
        Tag second = tag("second", TagStatus.ACTIVE);
        String id = projectId(create(owner, "PUBLIC", "ordered", null, null, List.of(second, first),
                List.of(feature("original", "original")), List.of(), "IN_PROGRESS", null, null));
        em.clear();
        patch(id, "{\"tagIds\":[\"" + first.getId() + "\",\"" + second.getId() + "\"]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.tags[0].id").value(first.getId().toString()));
        em.clear();
        patch(id, "{\"tagIds\":[\"" + second.getId() + "\",\"" + first.getId() + "\"],"
                + "\"features\":[{\"title\":\"changed\",\"description\":\"changed\"}]}").andExpect(status().isOk());
        em.clear();
        mvc.perform(get(PATH + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tags[0].id").value(second.getId().toString()))
                .andExpect(jsonPath("$.data.tags[1].id").value(first.getId().toString()))
                .andExpect(jsonPath("$.data.features[0].title").value("changed"));
        assertThat(jdbc.queryForList("select tag_id from project_tag where project_id=? order by display_order",
                Long.class, Long.parseLong(id))).containsExactly(second.getId(), first.getId());
    }

    @Test
    void requiresCompletedActiveUserForEveryWrite() throws Exception {
        String id = projectId(createRaw(owner, "{\"name\":\"owned\",\"lifecycleStatus\":\"IN_PROGRESS\",\"visibilityStatus\":\"PUBLIC\"}"));
        Member unfinished = members.saveAndFlush(new Member("unfinished", null, MemberStatus.ACTIVE, null, null));
        createRaw(unfinished, "{\"name\":\"new\",\"lifecycleStatus\":\"IN_PROGRESS\",\"visibilityStatus\":\"PUBLIC\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PROFILE_REQUIRED"));
        for (String state : List.of("SUSPENDED", "WITHDRAWAL_PENDING")) {
            jdbc.update("update member set status=?, withdrawal_requested_at=case when ?='WITHDRAWAL_PENDING' then now() end, "
                    + "withdrawal_scheduled_at=case when ?='WITHDRAWAL_PENDING' then now()+interval '7 days' end where id=?",
                    state, state, state, owner.getId());
            em.clear();
            patch(id, "{\"name\":\"denied\"}").andExpect(status().isForbidden());
            createRaw(owner, "{\"name\":\"denied\",\"lifecycleStatus\":\"IN_PROGRESS\",\"visibilityStatus\":\"PUBLIC\"}")
                    .andExpect(status().isForbidden());
            mvc.perform(delete(PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                    .andExpect(status().isForbidden());
            mvc.perform(get(PATH + "/" + id)).andExpect(state.equals("SUSPENDED") ? status().isOk() : status().isNotFound());
        }
    }

    @Test
    void retainsFirstPublicationAndUpdatesTimestampOnChildOnlyPatch() throws Exception {
        String id = projectId(createRaw(owner, "{\"name\":\"hidden\",\"lifecycleStatus\":\"IN_PROGRESS\",\"visibilityStatus\":\"HIDDEN\"}"));
        String first = response(patch(id, "{\"visibilityStatus\":\"PUBLIC\"}").andExpect(status().isOk()))
                .at("/data/publishedAt").asText();
        patch(id, "{\"visibilityStatus\":\"HIDDEN\"}").andExpect(status().isOk());
        patch(id, "{\"visibilityStatus\":\"PUBLIC\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publishedAt").value(first));
        em.flush();
        jdbc.update("update project set updated_at='2020-01-01T00:00:00Z' where id=?", Long.parseLong(id));
        em.clear();
        patch(id, "{\"features\":[{\"title\":\"new\",\"description\":\"new\"}]}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select updated_at > '2020-01-01T00:00:00Z' from project where id=?",
                Boolean.class, Long.parseLong(id))).isTrue();
    }

    @Test
    void validatesUnicodeNullableDatesUrlSchemesAndListParameters() throws Exception {
        String id = projectId(createRaw(owner, "{\"name\":\"" + "😀".repeat(120)
                + "\",\"lifecycleStatus\":\"IN_PROGRESS\",\"visibilityStatus\":\"PUBLIC\",\"startedOn\":null}"));
        for (String bad : List.of("{\"name\":\"" + "😀".repeat(121) + "\"}", "{\"name\":\"\\u0000\"}",
                "{\"name\":\"\\uD800\"}", "{\"startedOn\":\"+10000-01-01\"}", "{\"features\":null}",
                "{\"tagIds\":[1]}", "{\"links\":[{\"linkType\":\"OTHER\",\"url\":\"/relative\",\"displayOrder\":0}]}",
                "{\"links\":[{\"linkType\":\"OTHER\",\"url\":\"https://user:pass@example.com\",\"displayOrder\":0}]}")) {
            patch(id, bad).andExpect(status().isBadRequest());
        }
        for (String[] parameter : List.of(new String[]{"size", "101"}, new String[]{"page", "-1"},
                new String[]{"sort", "name,asc"}, new String[]{"lifecycleStatus", "DONE"},
                new String[]{"tagId", "0"}, new String[]{"visibilityStatus", "HIDDEN"})) {
            mvc.perform(get(PATH).param(parameter[0], parameter[1])).andExpect(status().isBadRequest());
        }
        mvc.perform(get(PATH).param("size", "1", "2")).andExpect(status().isBadRequest());
        mvc.perform(get(PATH).param("sort", "createdAt,asc")).andExpect(status().isOk());
        mvc.perform(get(PATH).param("tagId", "999999999")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(get(PATH + "/search")).andExpect(status().isBadRequest());
        mvc.perform(get(PATH + "/" + id)).andExpect(jsonPath("$.data.isBlocked").doesNotExist());
    }

    private ResultActions create(Member member, String visibility, String name, String summary, String description,
                                 List<Tag> selected, List<ObjectNode> features, List<ObjectNode> links,
                                 String lifecycle, String startedOn, String completedOn) throws Exception {
        ObjectNode json = mapper.createObjectNode().put("name", name).put("lifecycleStatus", lifecycle).put("visibilityStatus", visibility);
        if (summary != null) json.put("summary", summary);
        if (description != null) json.put("description", description);
        if (startedOn != null) json.put("startedOn", startedOn);
        if (completedOn != null) json.put("completedOn", completedOn);
        ArrayNode tagIds = json.putArray("tagIds");
        selected.forEach(tag -> tagIds.add(tag.getId().toString()));
        ArrayNode featureArray = json.putArray("features");
        features.forEach(featureArray::add);
        ArrayNode linkArray = json.putArray("links");
        links.forEach(linkArray::add);
        return createRaw(member, mapper.writeValueAsString(json));
    }

    private ResultActions createRaw(Member member, String body) throws Exception {
        return createRaw(member, body, member == null ? null : bearer(member));
    }

    private ResultActions createRaw(Member member, String body, String authorization) throws Exception {
        var request = post(PATH).contentType(MediaType.APPLICATION_JSON).content(body);
        if (authorization != null) request.header(HttpHeaders.AUTHORIZATION, authorization);
        return mvc.perform(request);
    }

    private ObjectNode feature(String title, String description) {
        return mapper.createObjectNode().put("title", title).put("description", description);
    }

    private ObjectNode link(String type, String label, String url, int displayOrder) {
        return mapper.createObjectNode().put("linkType", type).put("label", label).put("url", url).put("displayOrder", displayOrder);
    }

    private Tag tag(String name, TagStatus status) {
        return tags.saveAndFlush(new Tag(name, "tag-" + UUID.randomUUID(), 0, status));
    }

    private String projectId(ResultActions result) throws Exception {
        return response(result.andExpect(status().isCreated())).at("/data/id").asText();
    }

    private ResultActions patch(String id, String body) throws Exception {
        return patchAs(owner, id, body);
    }

    private ResultActions patchAs(Member member, String id, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(member))
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
