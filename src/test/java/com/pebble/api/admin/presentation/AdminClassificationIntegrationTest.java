package com.pebble.api.admin.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.admin.domain.AdminAccount;
import com.pebble.api.admin.domain.AdminRole;
import com.pebble.api.admin.infrastructure.persistence.AdminAccountRepository;
import com.pebble.api.auth.application.AdminRefreshTokenService;
import com.pebble.api.auth.application.AdminSessionService;
import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.category.domain.Category;
import com.pebble.api.category.infrastructure.persistence.CategoryRepository;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.post.domain.Post;
import com.pebble.api.post.domain.PostVisibility;
import com.pebble.api.post.infrastructure.persistence.PostRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
class AdminClassificationIntegrationTest extends AuthenticationTestSupport {
    private static final String PASSWORD = "Classification-Integration-2026";
    private static final String CATEGORIES = "/api/v1/admin/categories";
    private static final String TAGS = "/api/v1/admin/tags";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AdminAccountRepository accounts;
    @Autowired AdminSessionService sessions;
    @Autowired AccessTokenService accessTokens;
    @Autowired AdminRefreshTokenService refreshTokens;
    @Autowired PasswordEncoder passwords;
    @Autowired CategoryRepository categories;
    @Autowired MemberRepository members;
    @Autowired PostRepository posts;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entities;
    private AdminAccount manager;
    private AdminAccount master;
    private String managerToken;
    private String masterToken;

    @BeforeEach
    void setup() {
        manager = accounts.saveAndFlush(new AdminAccount("classification-manager-" + UUID.randomUUID(), passwords.encode(PASSWORD), AdminRole.MANAGER));
        master = accounts.saveAndFlush(new AdminAccount("classification-master-" + UUID.randomUUID(), passwords.encode(PASSWORD), AdminRole.MASTER));
        managerToken = sessions.login(manager.getLoginId(), PASSWORD).tokens().accessToken();
        masterToken = sessions.login(master.getLoginId(), PASSWORD).tokens().accessToken();
    }

    @AfterEach
    void cleanup() { refreshTokens.revokeAll(manager.getId()); refreshTokens.revokeAll(master.getId()); }

    @Test
    void managerAndMasterListAndCreateCategoriesIncludingInactiveBranches() throws Exception {
        String parent = create(CATEGORIES, managerToken, "{\"name\":\"Parent\",\"slug\":\"parent-" + suffix() + "\"}", 201);
        create(CATEGORIES, managerToken, "{\"name\":\"Child\",\"slug\":\"child-" + suffix() + "\",\"parentId\":\"" + parent + "\"}", 201);
        patchRequest(CATEGORIES, parent, managerToken, "{\"status\":\"INACTIVE\"}").andExpect(status().isOk());
        for (String token : new String[]{managerToken, masterToken}) {
            int index = listIndex(CATEGORIES, token, parent);
            mvc.perform(get(CATEGORIES).header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[" + index + "].status").value("INACTIVE"))
                    .andExpect(jsonPath("$.data[" + index + "].children[0].name").value("Child"));
        }
    }

    @Test
    void categoryPatchPreservesOmittedFieldsAndAllowsExplicitNullParent() throws Exception {
        String slug = "original-" + suffix();
        String id = create(CATEGORIES, managerToken, "{\"name\":\"Original\",\"slug\":\"" + slug + "\"}", 201);
        patchRequest(CATEGORIES, id, managerToken, "{\"name\":\"  Renamed  \"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Renamed")).andExpect(jsonPath("$.data.slug").value(slug));
        patchRequest(CATEGORIES, id, masterToken, "{\"parentId\":null}").andExpect(status().isOk());
        Timestamp unchangedAt = categoryUpdatedAt(id);
        entities.clear();
        patchRequest(CATEGORIES, id, managerToken, "{}").andExpect(status().isOk());
        entities.clear();
        org.assertj.core.api.Assertions.assertThat(categoryUpdatedAt(id)).isEqualTo(unchangedAt);
    }

    @Test
    void createsAndUpdatesTagsWithNormalizedSlugAndInactiveStatus() throws Exception {
        String slugSuffix = suffix();
        String id = create(TAGS, managerToken, "{\"name\":\"Tag\",\"slug\":\"MiXeD-" + slugSuffix + "\"}", 201);
        patchRequest(TAGS, id, masterToken, "{\"status\":\"INACTIVE\",\"displayOrder\":7}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slug").value("mixed-" + slugSuffix)).andExpect(jsonPath("$.data.status").value("INACTIVE"))
                .andExpect(jsonPath("$.data.displayOrder").value(7));
        int index = listIndex(TAGS, managerToken, id);
        mvc.perform(get(TAGS).header(HttpHeaders.AUTHORIZATION, bearer(managerToken))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[" + index + "].status").value("INACTIVE"));
    }

    @Test
    void rejectsUnknownDuplicateAndTrailingJsonAndInvalidFields() throws Exception {
        for (String body : new String[]{"{\"name\":\"a\",\"slug\":\"a\",\"extra\":1}",
                "{\"name\":\"a\",\"name\":\"b\",\"slug\":\"a\"}",
                "{\"name\":\"a\",\"slug\":\"a\"} {}", "{\"name\":\"x\",\"slug\":\"bad slug\"}",
                "{\"name\":\"x\",\"slug\":\"x\",\"displayOrder\":-1}",
                "{\"name\":\"x\",\"slug\":\"x\",\"status\":\"ACTIVE\"}"}) {
            mvc.perform(post(CATEGORIES).contentType(MediaType.APPLICATION_JSON).content(body)
                    .header(HttpHeaders.AUTHORIZATION, bearer(managerToken))).andExpect(status().isBadRequest());
        }
        for (String body : new String[]{"{\"name\":\"x\",\"slug\":\"x\",\"parentId\":0}",
                "{\"name\":\"x\",\"slug\":\"x\",\"parentId\":\"01\"}",
                "{\"name\":\"x\",\"slug\":\"x\",\"parentId\":\"9223372036854775808\"}"}) {
            mvc.perform(post(CATEGORIES).contentType(MediaType.APPLICATION_JSON).content(body)
                    .header(HttpHeaders.AUTHORIZATION, bearer(managerToken))).andExpect(status().isBadRequest());
        }
    }

    @Test
    void rejectsBadPatchIdsAndQueryParameters() throws Exception {
        for (String id : new String[]{"0", "01", "9223372036854775808"}) {
            mvc.perform(patch(CATEGORIES + "/" + id).contentType(MediaType.APPLICATION_JSON).content("{}")
                    .header(HttpHeaders.AUTHORIZATION, bearer(managerToken))).andExpect(status().isBadRequest());
        }
        mvc.perform(get(CATEGORIES).queryParam("q", "anything").header(HttpHeaders.AUTHORIZATION, bearer(managerToken)))
                .andExpect(status().isBadRequest());
        mvc.perform(get(TAGS).content("unexpected").header(HttpHeaders.AUTHORIZATION, bearer(managerToken)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMalformedUnicodeAndLimitsCodePointsAndSlugLength() throws Exception {
        for (String name : new String[]{"", "\u0000", "\uD800", "x".repeat(51)}) {
            String body = "{\"name\":" + quote(name) + ",\"slug\":\"unicode-check\"}";
            mvc.perform(post(TAGS).contentType(MediaType.APPLICATION_JSON).content(body)
                    .header(HttpHeaders.AUTHORIZATION, bearer(managerToken))).andExpect(status().isBadRequest());
        }
        String tooLongSlug = "a".repeat(101);
        mvc.perform(post(TAGS).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\",\"slug\":\"" + tooLongSlug + "\"}")
                .header(HttpHeaders.AUTHORIZATION, bearer(managerToken))).andExpect(status().isBadRequest());
        String emoji = "😀";
        create(TAGS, managerToken, "{\"name\":" + quote(emoji.repeat(50)) + ",\"slug\":\"emoji-" + suffix() + "\"}", 201);
        mvc.perform(post(TAGS).contentType(MediaType.APPLICATION_JSON).content("{\"name\":" + quote(emoji.repeat(51))
                        + ",\"slug\":\"emoji-" + suffix() + "\"}").header(HttpHeaders.AUTHORIZATION, bearer(managerToken)))
                .andExpect(status().isBadRequest());
        String followedByNul = "😀\0x";
        mvc.perform(post(TAGS).contentType(MediaType.APPLICATION_JSON).content("{\"name\":" + quote(followedByNul)
                        + ",\"slug\":\"nul-" + suffix() + "\"}").header(HttpHeaders.AUTHORIZATION, bearer(managerToken)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsDuplicateSlugsAndCategoryCyclesAndNonLeafParents() throws Exception {
        String root = create(CATEGORIES, managerToken, "{\"name\":\"Root\",\"slug\":\"root-" + suffix() + "\"}", 201);
        String child = create(CATEGORIES, managerToken, "{\"name\":\"Child\",\"slug\":\"child-" + suffix() + "\",\"parentId\":\"" + root + "\"}", 201);
        create(CATEGORIES, managerToken, "{\"name\":\"Grandchild\",\"slug\":\"grandchild-" + suffix() + "\",\"parentId\":\"" + child + "\"}", 409);
        patchRequest(CATEGORIES, root, managerToken, "{\"parentId\":\"" + child + "\"}").andExpect(status().isConflict());
        String slug = "duplicate-" + suffix();
        create(TAGS, managerToken, "{\"name\":\"First\",\"slug\":\"" + slug + "\"}", 201);
        create(TAGS, masterToken, "{\"name\":\"Second\",\"slug\":\"" + slug + "\"}", 409);
    }

    @Test
    void publicTreeHidesUnusedInactiveCategoryAndKeepsInactiveCategoryUsedByPublicPost() throws Exception {
        String hidden = create(CATEGORIES, managerToken, "{\"name\":\"Hidden inactive\",\"slug\":\"hidden-" + suffix() + "\"}", 201);
        String used = create(CATEGORIES, managerToken, "{\"name\":\"Used inactive\",\"slug\":\"used-" + suffix() + "\"}", 201);
        patchRequest(CATEGORIES, hidden, managerToken, "{\"status\":\"INACTIVE\"}").andExpect(status().isOk());
        patchRequest(CATEGORIES, used, managerToken, "{\"status\":\"INACTIVE\"}").andExpect(status().isOk());
        Member member = members.saveAndFlush(new Member("classification-" + suffix(), null, MemberStatus.ACTIVE, null, null));
        Category usedCategory = categories.findById(Long.parseLong(used)).orElseThrow();
        long postNumber = System.nanoTime();
        posts.saveAndFlush(new Post(member, usedCategory, "used classification", null, PostVisibility.PUBLIC,
                "classification-" + suffix(), postNumber));
        JsonNode tree = mapper.readTree(mvc.perform(get("/api/v1/categories")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
        org.assertj.core.api.Assertions.assertThat(containsId(tree, hidden)).isFalse();
        org.assertj.core.api.Assertions.assertThat(containsId(tree, used)).isTrue();
    }

    @Test
    void addingChildConflictsWhenParentHasPostsInAnyVisibilityState() throws Exception {
        Member member = members.saveAndFlush(new Member("class-posts-" + suffix(), null, MemberStatus.ACTIVE, null, null));
        for (PostVisibility visibility : new PostVisibility[]{PostVisibility.PUBLIC, PostVisibility.HIDDEN}) {
            String parent = create(CATEGORIES, managerToken, "{\"name\":\"Parent\",\"slug\":\"used-" + suffix() + "\"}", 201);
            Category category = categories.findById(Long.parseLong(parent)).orElseThrow();
            posts.saveAndFlush(new Post(member, category, "post " + suffix(), null, visibility,
                    "post-" + suffix(), System.nanoTime()));
            assertChildConflict(parent);
        }
        String deletedParent = create(CATEGORIES, managerToken, "{\"name\":\"Deleted parent\",\"slug\":\"deleted-" + suffix() + "\"}", 201);
        Category deletedCategory = categories.findById(Long.parseLong(deletedParent)).orElseThrow();
        Post deleted = posts.saveAndFlush(new Post(member, deletedCategory, "deleted post", null, PostVisibility.HIDDEN,
                "post-" + suffix(), System.nanoTime()));
        jdbc.update("update post set visibility_status='DELETED',deleted_at=now() where id=?", deleted.getId());
        assertChildConflict(deletedParent);
    }

    @Test
    void restrictsRoutesMethodsAuthenticationRolesAndCors() throws Exception {
        mvc.perform(get(CATEGORIES)).andExpect(status().isUnauthorized());
        String id = create(TAGS, managerToken, "{\"name\":\"role\",\"slug\":\"role-" + UUID.randomUUID().toString().substring(0, 8) + "\"}", 201);
        mvc.perform(patch(TAGS + "/" + id).contentType(MediaType.APPLICATION_JSON).content("{}")
                .header(HttpHeaders.AUTHORIZATION, bearer("eyJhbGciOiJSUzI1NiJ9.invalid.signature"))).andExpect(status().isUnauthorized());
        mvc.perform(options(CATEGORIES).header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")).andExpect(status().isForbidden());
        mvc.perform(get(CATEGORIES + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(managerToken))).andExpect(status().isForbidden());
    }

    @Test
    void userAndCookieCannotOperateClassificationsAndRevokedAdminSessionIsRejected() throws Exception {
        Member member = members.saveAndFlush(new Member("class-user-" + suffix(), null, MemberStatus.ACTIVE, null, null));
        String user = accessTokens.issueForMember(member.getId(), java.time.Instant.now());
        for (String path : new String[]{CATEGORIES, TAGS}) {
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(user))).andExpect(status().isForbidden());
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\",\"slug\":\"x\"}")
                    .header(HttpHeaders.AUTHORIZATION, bearer(user))).andExpect(status().isForbidden());
            mvc.perform(patch(path + "/1").contentType(MediaType.APPLICATION_JSON).content("{}")
                    .header(HttpHeaders.AUTHORIZATION, bearer(user))).andExpect(status().isForbidden());
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}")
                    .cookie(new jakarta.servlet.http.Cookie("admin_refresh_token", "not-access"))).andExpect(status().isForbidden());
            mvc.perform(delete(path + "/1").header(HttpHeaders.AUTHORIZATION, bearer(managerToken))).andExpect(status().isForbidden());
        }
        refreshTokens.revokeAll(manager.getId());
        mvc.perform(get(CATEGORIES).header(HttpHeaders.AUTHORIZATION, bearer(managerToken))).andExpect(status().isUnauthorized());
    }

    @Test
    void corsAllowsOnlyConfiguredClassificationMethodsAndNumericPatchPaths() throws Exception {
        for (String path : new String[]{CATEGORIES, TAGS}) {
            for (String method : new String[]{"GET", "POST"}) {
                mvc.perform(options(path).header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization,Content-Type"))
                        .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"));
            }
            mvc.perform(options(path + "/1").header(HttpHeaders.ORIGIN, "http://localhost:3000")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH")).andExpect(status().isOk());
            mvc.perform(options(path + "/1").header(HttpHeaders.ORIGIN, "http://localhost:3000")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")).andExpect(status().isForbidden());
            mvc.perform(options(path + "/invalid").header(HttpHeaders.ORIGIN, "http://localhost:3000")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH")).andExpect(status().isForbidden());
            mvc.perform(options(path).header(HttpHeaders.ORIGIN, "http://localhost:3000")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "DELETE")).andExpect(status().isForbidden());
        }
    }

    private String create(String path, String token, String body, int expected) throws Exception {
        var result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().is(expected)).andReturn();
        if (expected != 201) return null;
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isNotNull();
        String location = result.getResponse().getHeader(HttpHeaders.LOCATION);
        return location.substring(location.lastIndexOf('/') + 1);
    }
    private org.springframework.test.web.servlet.ResultActions patchRequest(String path, String id, String token, String body) throws Exception {
        return mvc.perform(patch(path + "/" + id).contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }
    private String bearer(String token) { return "Bearer " + token; }
    private int listIndex(String path, String token, String id) throws Exception {
        JsonNode entries = mapper.readTree(mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        for (int i = 0; i < entries.size(); i++) if (id.equals(entries.get(i).path("id").asText())) return i;
        throw new AssertionError("Missing classification " + id);
    }
    private Timestamp categoryUpdatedAt(String id) {
        return jdbc.queryForObject("select updated_at from category where id=?", Timestamp.class, Long.parseLong(id));
    }
    private static boolean containsId(JsonNode entries, String id) {
        for (JsonNode entry : entries) {
            if (id.equals(entry.path("id").asText()) || containsId(entry.path("children"), id)) return true;
        }
        return false;
    }
    private void assertChildConflict(String parent) throws Exception {
        mvc.perform(post(CATEGORIES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Child\",\"slug\":\"child-" + suffix() + "\",\"parentId\":\"" + parent + "\"}")
                        .header(HttpHeaders.AUTHORIZATION, bearer(managerToken)))
                .andExpect(status().isConflict());
    }
    private static String quote(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') quoted.append('\\').append(c);
            else if (c < 32 || Character.isSurrogate(c)) quoted.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            else quoted.append(c);
        }
        return quoted.append('"').toString();
    }
    private static String suffix() { return UUID.randomUUID().toString().replace("-", "").substring(0, 10); }
}
