package com.pebble.api.admin.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.admin.application.AdminAccountService;
import com.pebble.api.admin.domain.AdminAccount;
import com.pebble.api.admin.domain.AdminRole;
import com.pebble.api.admin.domain.AdminStatus;
import com.pebble.api.admin.infrastructure.persistence.AdminAccountRepository;
import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.auth.application.AdminRefreshTokenService;
import com.pebble.api.auth.application.AdminSessionService;
import com.pebble.api.support.AuthenticationTestSupport;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminAccountManagementIntegrationTest extends AuthenticationTestSupport {
    private static final String BASE = "/api/v1/admin/admin-accounts";
    private static final String ORIGIN = "http://localhost:3000";
    private static final String MASTER_PASSWORD = "Master-Integration-Secret-2026";
    private static final String MANAGER_PASSWORD = "Manager-Integration-Secret-2026";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AdminAccountRepository accounts;
    @Autowired AdminSessionService sessions;
    @Autowired AdminRefreshTokenService refreshTokens;
    @Autowired AccessTokenService accessTokens;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private AdminAccount master;
    private AdminAccount otherMaster;
    private AdminAccount manager;
    private String masterAccess;
    private String managerAccess;
    private String userAccess;
    private final List<Long> sessionsToRevoke = new ArrayList<>();

    @BeforeEach
    void createActorsAndAccessTokens() {
        master = save("test-master-" + suffix(), MASTER_PASSWORD, AdminRole.MASTER);
        otherMaster = save("other-master-" + suffix(), MASTER_PASSWORD, AdminRole.MASTER);
        manager = save("test-manager-" + suffix(), MANAGER_PASSWORD, AdminRole.MANAGER);
        masterAccess = login(master.getLoginId(), MASTER_PASSWORD);
        managerAccess = login(manager.getLoginId(), MANAGER_PASSWORD);
        userAccess = accessTokens.issueForMember(734_811L, Instant.now());
    }

    @AfterEach
    void revokeTestSessions() {
        for (Long id : sessionsToRevoke) refreshTokens.revokeAll(id);
    }

    @Test
    void listsAllStatusesWithoutCredentialFieldsAndUsesStableDefaultAndRequestedSorts() throws Exception {
        var result = mvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(masterAccess)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andReturn();
        JsonNode defaultPage = data(result);
        assertThat(defaultPage.path("content").isArray()).isTrue();
        assertThat(defaultPage.toString()).doesNotContain("passwordHash", "initialPassword", MASTER_PASSWORD, MANAGER_PASSWORD);
        assertThat(defaultPage.path("content").findValuesAsText("role")).contains("MASTER", "MANAGER");
        AdminAccount inactive = save("inactive-" + suffix(), MANAGER_PASSWORD, AdminRole.MANAGER);
        inactive.changeManagerStatus(AdminStatus.INACTIVE);
        accounts.saveAndFlush(inactive);
        entityManager.clear();
        result = mvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(masterAccess)))
                .andExpect(status().isOk()).andReturn();
        defaultPage = data(result);
        assertThat(defaultPage.path("content").findValuesAsText("status")).contains("ACTIVE", "INACTIVE");

        AdminAccount first = save("sort-a-" + suffix(), MANAGER_PASSWORD, AdminRole.MANAGER);
        AdminAccount second = save("sort-b-" + suffix(), MANAGER_PASSWORD, AdminRole.MANAGER);
        jdbc.update("update admin_account set created_at=? where id=?", java.sql.Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")), first.getId());
        jdbc.update("update admin_account set created_at=? where id=?", java.sql.Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")), second.getId());
        jdbc.update("update admin_account set updated_at=? where id=?", java.sql.Timestamp.from(Instant.parse("2021-01-01T00:00:00Z")), first.getId());
        jdbc.update("update admin_account set updated_at=? where id=?", java.sql.Timestamp.from(Instant.parse("2021-01-01T00:00:00Z")), second.getId());
        entityManager.clear();

        JsonNode defaultSort = data(mvc.perform(get(BASE).queryParam("size", "100")
                .header(HttpHeaders.AUTHORIZATION, bearer(masterAccess))).andExpect(status().isOk()).andReturn());
        assertRelativeOrder(defaultSort, first.getLoginId(), second.getLoginId(), first.getId() > second.getId());
        mvc.perform(get(BASE).queryParam("page", "1").queryParam("size", "1")
                .header(HttpHeaders.AUTHORIZATION, bearer(masterAccess))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1)).andExpect(jsonPath("$.data.size").value(1));

        JsonNode asc = data(mvc.perform(get(BASE).queryParam("size", "100").queryParam("sort", "createdAt,asc")
                .header(HttpHeaders.AUTHORIZATION, bearer(masterAccess))).andExpect(status().isOk()).andReturn());
        assertRelativeOrder(asc, first.getLoginId(), second.getLoginId(), first.getId() < second.getId());
        JsonNode desc = data(mvc.perform(get(BASE).queryParam("size", "100").queryParam("sort", "createdAt,desc")
                .header(HttpHeaders.AUTHORIZATION, bearer(masterAccess))).andExpect(status().isOk()).andReturn());
        assertRelativeOrder(desc, first.getLoginId(), second.getLoginId(), first.getId() > second.getId());
        JsonNode loginIdOrder = data(mvc.perform(get(BASE).queryParam("size", "100").queryParam("sort", "loginId,asc")
                .header(HttpHeaders.AUTHORIZATION, bearer(masterAccess))).andExpect(status().isOk()).andReturn());
        assertRelativeOrder(loginIdOrder, first.getLoginId(), second.getLoginId(), true);
        JsonNode updatedOrder = data(mvc.perform(get(BASE).queryParam("size", "100").queryParam("sort", "updatedAt,desc")
                .header(HttpHeaders.AUTHORIZATION, bearer(masterAccess))).andExpect(status().isOk()).andReturn());
        assertRelativeOrder(updatedOrder, first.getLoginId(), second.getLoginId(), first.getId() > second.getId());
    }

    @Test
    void validatesPagingSortQueryMultiplicityAndGetBody() throws Exception {
        for (String query : List.of("?unknown=1", "?page=0&page=1", "?size=101", "?size=0", "?page=-1",
                "?page=one", "?sort=unknown,asc", "?sort=createdAt,sideways", "?sort=createdAt", "?sort=createdAt,asc,loginId")) {
            mvc.perform(get(BASE + query).header(HttpHeaders.AUTHORIZATION, bearer(masterAccess)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get(BASE + "?page=2147483647&size=2").header(HttpHeaders.AUTHORIZATION, bearer(masterAccess)))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "?sort=createdAt,asc&sort=createdAt,desc")
                .header(HttpHeaders.AUTHORIZATION, bearer(masterAccess))).andExpect(status().isBadRequest());
        mvc.perform(get(BASE).content("unexpected").header(HttpHeaders.AUTHORIZATION, bearer(masterAccess)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createsManagerWithStrictCredentialsFixedRoleAndLocationAndRejectsDuplicates() throws Exception {
        String loginId = "created-" + suffix();
        String initialPassword = "초기-Admin-비밀번호-2026";
        var response = mvc.perform(create(loginId, initialPassword, masterAccess))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.data.loginId").value(loginId))
                .andExpect(jsonPath("$.data.role").value("MANAGER"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.updatedAt").isNotEmpty())
                .andReturn();
        JsonNode created = data(response);
        assertThat(response.getResponse().getHeader(HttpHeaders.LOCATION)).endsWith("/" + created.path("id").asText());
        assertThat(created.toString()).doesNotContain("passwordHash", "initialPassword", initialPassword);
        AdminAccount saved = accounts.findByLoginId(loginId).orElseThrow();
        assertThat(passwords.matches(initialPassword, saved.getPasswordHash())).isTrue();
        assertThat(saved.getPasswordHash()).isNotEqualTo(initialPassword);

        mvc.perform(create(loginId, initialPassword, masterAccess)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_RESOURCE"));
    }

    @Test
    void rejectsInvalidCreateBodiesAndQueryWithoutCreatingAccounts() throws Exception {
        List<String> invalidBodies = List.of(
                "null", "[]", "{}", "{\"loginId\":\"valid-admin\",\"initialPassword\":null}",
                "{\"loginId\":\"valid-admin\",\"initialPassword\":\"too-short\"}",
                "{\"loginId\":\"valid-admin\",\"initialPassword\":\"" + "x".repeat(129) + "\"}",
                "{\"loginId\":\"valid-admin\",\"initialPassword\":\"valid-password-2026\",\"role\":\"MASTER\"}",
                "{\"loginId\":\"valid-admin\",\"loginId\":\"valid-admin\",\"initialPassword\":\"valid-password-2026\"}",
                "{\"loginId\":\"valid-admin\",\"initialPassword\":\"valid-password-2026\"} {}",
                "{\"loginId\":\"bad id\",\"initialPassword\":\"valid-password-2026\"}",
                "{\"loginId\":\"valid-admin\",\"initialPassword\":\"bad\\u0000password-2026\"}",
                "{\"loginId\":\"valid-admin\",\"initialPassword\":\"\\uD800-very-long-password\"}");
        for (String body : invalidBodies) {
            mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body)
                    .header(HttpHeaders.AUTHORIZATION, bearer(masterAccess)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(create("valid-admin", "valid-password-2026", masterAccess).queryParam("unknown", "1"))
                .andExpect(status().isBadRequest());
        assertThat(accounts.findByLoginId("valid-admin")).isEmpty();
    }

    @Test
    void changesManagerStatusAndRequiresFreshLoginAfterReactivation() throws Exception {
        var inactive = mvc.perform(statusChange(manager.getId(), "INACTIVE", masterAccess))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("INACTIVE")).andReturn();
        assertThat(data(inactive).path("id").asText()).isEqualTo(manager.getId().toString());
        mvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(managerAccess))).andExpect(status().isUnauthorized());

        mvc.perform(statusChange(manager.getId(), "ACTIVE", masterAccess))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACTIVE"));
        mvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(managerAccess))).andExpect(status().isUnauthorized());
        String fresh = login(manager.getLoginId(), MANAGER_PASSWORD);
        mvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(fresh))).andExpect(status().isForbidden());
    }

    @Test
    void settingTheSameActiveStatusRevokesSessionsWithoutChangingUpdateTimestamp() throws Exception {
        var before = jdbc.queryForObject("select updated_at from admin_account where id=?", java.sql.Timestamp.class, manager.getId());
        mvc.perform(statusChange(manager.getId(), "ACTIVE", masterAccess)).andExpect(status().isOk());
        var after = jdbc.queryForObject("select updated_at from admin_account where id=?", java.sql.Timestamp.class, manager.getId());
        assertThat(after).isEqualTo(before);
        mvc.perform(get("/api/v1/categories").header(HttpHeaders.AUTHORIZATION, bearer(managerAccess)))
                .andExpect(status().isUnauthorized());
        String fresh = login(manager.getLoginId(), MANAGER_PASSWORD);
        mvc.perform(get("/api/v1/categories").header(HttpHeaders.AUTHORIZATION, bearer(fresh))).andExpect(status().isOk());
        mvc.perform(statusChange(manager.getId(), "ACTIVE", masterAccess)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/categories").header(HttpHeaders.AUTHORIZATION, bearer(fresh))).andExpect(status().isUnauthorized());
    }

    @Test
    void statusRouteRejectsSelfMastersMissingTargetsMalformedIdsAndInvalidBodies() throws Exception {
        for (String id : List.of(master.getId().toString(), otherMaster.getId().toString(), "999999999999999999")) {
            mvc.perform(statusChangeRaw(id, "{\"status\":\"INACTIVE\"}", masterAccess)).andExpect(status().isNotFound());
        }
        for (String id : List.of("0", "000" + manager.getId(), "9223372036854775808")) {
            mvc.perform(statusChangeRaw(id, "{\"status\":\"INACTIVE\"}", masterAccess)).andExpect(status().isBadRequest());
        }
        for (String id : List.of("abc", "-1")) {
            mvc.perform(statusChangeRaw(id, "{\"status\":\"INACTIVE\"}", masterAccess)).andExpect(status().isForbidden());
        }
        for (String body : List.of("", "null", "{}", "{\"status\":null}", "{\"status\":\"MASTER\"}",
                "{\"status\":\"ACTIVE\",\"role\":\"MASTER\"}",
                "{\"status\":\"ACTIVE\",\"status\":\"INACTIVE\"}", "{\"status\":\"ACTIVE\"} []")) {
            mvc.perform(statusChangeRaw(manager.getId().toString(), body, masterAccess)).andExpect(status().isBadRequest());
        }
        mvc.perform(patch(BASE + "/" + manager.getId() + "/status?unknown=1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"INACTIVE\"}").header(HttpHeaders.AUTHORIZATION, bearer(masterAccess)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiresMasterBearerAndRejectsCookieOnlyAndUserOrManagerRoles() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE).cookie(new jakarta.servlet.http.Cookie("admin_refresh_token", "not-an-access-token")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(managerAccess))).andExpect(status().isForbidden());
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{}")
                .header(HttpHeaders.AUTHORIZATION, bearer(managerAccess)))
                .andExpect(status().isForbidden());
        mvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(userAccess))).andExpect(status().isForbidden());
        mvc.perform(patch(BASE + "/" + manager.getId() + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"INACTIVE\"}").header(HttpHeaders.AUTHORIZATION, bearer(userAccess)))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void corsAllowsOnlyConfiguredOriginAndRouteMethods() throws Exception {
        mvc.perform(options(BASE).header(HttpHeaders.ORIGIN, ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN));
        mvc.perform(options(BASE + "/" + manager.getId() + "/status").header(HttpHeaders.ORIGIN, ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                .andExpect(status().isOk());
        mvc.perform(options(BASE).header(HttpHeaders.ORIGIN, "https://foreign.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
        mvc.perform(options(BASE).header(HttpHeaders.ORIGIN, ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH"))
                .andExpect(status().isForbidden());
    }

    private AdminAccount save(String loginId, String password, AdminRole role) {
        return accounts.saveAndFlush(new AdminAccount(loginId, passwords.encode(password), role));
    }

    private String login(String loginId, String password) {
        long id = accounts.findByLoginId(loginId).orElseThrow().getId();
        sessionsToRevoke.add(id);
        return sessions.login(loginId, password).tokens().accessToken();
    }

    private MockHttpServletRequestBuilder create(String loginId, String password, String token) throws Exception {
        return post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("loginId", loginId, "initialPassword", password)))
                .header(HttpHeaders.AUTHORIZATION, bearer(token));
    }

    private MockHttpServletRequestBuilder statusChange(long id, String status, String token) {
        return statusChangeRaw(Long.toString(id), "{\"status\":\"" + status + "\"}", token);
    }

    private MockHttpServletRequestBuilder statusChangeRaw(String id, String body, String token) {
        return patch(BASE + "/" + id + "/status").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HttpHeaders.AUTHORIZATION, bearer(token));
    }

    private String bearer(String accessToken) { return "Bearer " + accessToken; }
    private String suffix() { return UUID.randomUUID().toString().replace("-", "").substring(0, 12); }

    private JsonNode data(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private void assertRelativeOrder(JsonNode page, String first, String second, boolean firstBeforeSecond) {
        int firstIndex = -1;
        int secondIndex = -1;
        for (int i = 0; i < page.path("content").size(); i++) {
            String loginId = page.path("content").get(i).path("loginId").asText();
            if (first.equals(loginId)) firstIndex = i;
            if (second.equals(loginId)) secondIndex = i;
        }
        assertThat(firstIndex).isGreaterThanOrEqualTo(0);
        assertThat(secondIndex).isGreaterThanOrEqualTo(0);
        assertThat(firstIndex < secondIndex).isEqualTo(firstBeforeSecond);
    }

}
