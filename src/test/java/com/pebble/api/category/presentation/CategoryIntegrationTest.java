package com.pebble.api.category.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.auth.application.oauth.OAuthProviderClient;
import com.pebble.api.category.domain.Category;
import com.pebble.api.category.domain.CategoryStatus;
import com.pebble.api.category.infrastructure.persistence.CategoryRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CategoryIntegrationTest extends AuthenticationTestSupport {

    private static final String PATH = "/api/v1/categories";
    private static final String ORIGIN = "http://localhost:3000";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AccessTokenService accessTokens;

    @MockitoBean(name = "naverOAuthClient")
    private OAuthProviderClient naverOAuthGateway;

    @BeforeEach
    void clearTreeWithinRollbackTransaction() {
        jdbc.update("delete from category where parent_id is not null");
        categories.deleteAllInBatch();
    }

    @Test
    void anonymousGuestCanReadAnEmptyTree() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void returnsTwoLevelsWithStableSiblingOrderStringIdsAndNoEntityFields() throws Exception {
        Category later = root("Later", "test-later", 2, CategoryStatus.ACTIVE);
        Category first = root("First", "test-first", 1, CategoryStatus.ACTIVE);
        Category sameOrder = root("Same", "test-same", 1, CategoryStatus.ACTIVE);
        Category childLater = child(first, "Later child", "test-child-later", 2, CategoryStatus.ACTIVE);
        Category childFirst = child(first, "First child", "test-child-first", 1, CategoryStatus.ACTIVE);
        Category smaller = first.getId() < sameOrder.getId() ? first : sameOrder;
        Category larger = smaller == first ? sameOrder : first;
        int firstIndex = smaller == first ? 0 : 1;

        mockMvc.perform(get(PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].id").value(smaller.getId().toString()))
                .andExpect(jsonPath("$.data[1].id").value(larger.getId().toString()))
                .andExpect(jsonPath("$.data[2].id").value(later.getId().toString()))
                .andExpect(jsonPath("$.data[2].children").isEmpty())
                .andExpect(jsonPath("$.data[0].parentId").isEmpty())
                .andExpect(jsonPath("$.data[0].length()").value(7))
                .andExpect(jsonPath("$.data[" + firstIndex + "].children[0].id").value(childFirst.getId().toString()))
                .andExpect(jsonPath("$.data[" + firstIndex + "].children[0].parentId").value(first.getId().toString()))
                .andExpect(jsonPath("$.data[" + firstIndex + "].children[0].name").value("First child"))
                .andExpect(jsonPath("$.data[" + firstIndex + "].children[0].slug").value("test-child-first"))
                .andExpect(jsonPath("$.data[" + firstIndex + "].children[0].displayOrder").value(1))
                .andExpect(jsonPath("$.data[" + firstIndex + "].children[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[" + firstIndex + "].children[0].children").isEmpty())
                .andExpect(jsonPath("$.data[" + firstIndex + "].children[1].id").value(childLater.getId().toString()));
    }

    @Test
    void keepsInactiveAncestorForAnActiveChildWithoutShowingOtherUnusedInactiveNodes() throws Exception {
        Category inactiveRoot = root("Inactive parent", "test-inactive-parent", 0, CategoryStatus.INACTIVE);
        Category activeChild = child(inactiveRoot, "Active child", "test-active-child", 0, CategoryStatus.ACTIVE);
        child(inactiveRoot, "Inactive child", "test-inactive-child", 1, CategoryStatus.INACTIVE);
        root("Unused inactive", "test-unused-inactive", 1, CategoryStatus.INACTIVE);

        mockMvc.perform(get(PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(inactiveRoot.getId().toString()))
                .andExpect(jsonPath("$.data[0].status").value("INACTIVE"))
                .andExpect(jsonPath("$.data[0].children.length()").value(1))
                .andExpect(jsonPath("$.data[0].children[0].id").value(activeChild.getId().toString()))
                .andExpect(jsonPath("$.data[0].children[0].status").value("ACTIVE"));
    }

    @Test
    void permitsOnlyAllowedOriginGetCors() throws Exception {
        mockMvc.perform(options(PATH).header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET"));
        mockMvc.perform(get(PATH).header(HttpHeaders.ORIGIN, ORIGIN)).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN));
        mockMvc.perform(options(PATH).header(HttpHeaders.ORIGIN, "https://untrusted.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
        mockMvc.perform(options(PATH).header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void doesNotOpenUserWritesOrAdminRoutes() throws Exception {
        String bearer = "Bearer " + accessTokens.issueForMember(Long.MAX_VALUE, Instant.now());
        mockMvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/tags").header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/categories").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/tags").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isForbidden());
    }

    private Category root(String name, String slug, int order, CategoryStatus status) {
        return categories.saveAndFlush(new Category(null, name, slug, order, status));
    }

    private Category child(Category parent, String name, String slug, int order, CategoryStatus status) {
        return categories.saveAndFlush(new Category(parent, name, slug, order, status));
    }
}
