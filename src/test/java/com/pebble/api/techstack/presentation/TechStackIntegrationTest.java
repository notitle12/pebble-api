package com.pebble.api.techstack.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pebble.api.auth.application.oauth.OAuthProviderClient;
import com.pebble.api.support.AuthenticationTestSupport;
import com.pebble.api.techstack.domain.TechStack;
import com.pebble.api.techstack.domain.TechStackStatus;
import com.pebble.api.techstack.infrastructure.persistence.TechStackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TechStackIntegrationTest extends AuthenticationTestSupport {

    private static final String PATH = "/api/v1/tech-stacks";
    private static final String ORIGIN = "http://localhost:3000";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TechStackRepository techStacks;

    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @MockitoBean(name = "naverOAuthClient")
    private OAuthProviderClient naverOAuthGateway;

    @BeforeEach
    void clearTechStacksWithinRollbackTransaction() {
        techStacks.deleteAllInBatch();
    }

    @Test
    void catalogCurationPreservesIdsAndInactiveChoicesAndCanBeReplayed() {
        TechStack temporary = techStacks.saveAndFlush(new TechStack("똥", "test-junk", 0, TechStackStatus.ACTIVE));
        techStacks.saveAndFlush(new TechStack("React", "custom-react", 1, TechStackStatus.INACTIVE));
        techStacks.saveAndFlush(new TechStack("Spring Boot", "spring-boot", 2, TechStackStatus.ACTIVE));
        TechStack duplicate = techStacks.saveAndFlush(new TechStack("springboot", "test-duplicate", 3, TechStackStatus.ACTIVE));
        Runnable apply = () -> jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.ClassPathResource("db/migration/V31__curate_tech_stack_catalog.sql"));
            return null;
        });
        apply.run();
        Long count = jdbc.queryForObject("SELECT count(*) FROM tech_stack", Long.class);
        assertThat(count).isGreaterThan(70);
        assertThat(jdbc.queryForObject("SELECT status FROM tech_stack WHERE id=?", String.class, temporary.getId())).isEqualTo("INACTIVE");
        assertThat(jdbc.queryForObject("SELECT status FROM tech_stack WHERE id=?", String.class, duplicate.getId())).isEqualTo("INACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tech_stack WHERE name='React'", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT status FROM tech_stack WHERE name='React'", String.class)).isEqualTo("INACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tech_stack WHERE name IN ('TypeScript','FastAPI','PyTorch','Cloudflare') AND status='ACTIVE'", Long.class)).isEqualTo(4L);
        apply.run();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tech_stack", Long.class)).isEqualTo(count);
    }

    @Test
    void anonymousGuestCanReadAnEmptyList() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void returnsActiveTechStacksWithStableOrderAndStringIds() throws Exception {
        techStacks.saveAndFlush(new TechStack("Later", "test-later", 2, TechStackStatus.ACTIVE));
        TechStack first = techStacks.saveAndFlush(new TechStack("First", "test-first", 1, TechStackStatus.ACTIVE));
        TechStack second = techStacks.saveAndFlush(new TechStack("Second", "test-second", 1, TechStackStatus.ACTIVE));
        techStacks.saveAndFlush(new TechStack("Inactive", "test-inactive", 0, TechStackStatus.INACTIVE));
        TechStack smaller = first.getId() < second.getId() ? first : second;
        TechStack larger = smaller == first ? second : first;

        mockMvc.perform(get(PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].id").value(smaller.getId().toString()))
                .andExpect(jsonPath("$.data[1].id").value(larger.getId().toString()))
                .andExpect(jsonPath("$.data[0].name").value(smaller.getName()))
                .andExpect(jsonPath("$.data[0].slug").value(smaller.getSlug()))
                .andExpect(jsonPath("$.data[0].displayOrder").value(1))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].length()").value(5))
                .andExpect(jsonPath("$.data[2].slug").value("test-later"));
    }

    @Test
    void permitsFrontendGetButRejectsPublicCatalogWrites() throws Exception {
        mockMvc.perform(options(PATH).header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET"));
        mockMvc.perform(get(PATH).header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN));
        mockMvc.perform(options(PATH).header(HttpHeaders.ORIGIN, "https://untrusted.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
        mockMvc.perform(options(PATH).header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
        mockMvc.perform(options(PATH).header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "DELETE"))
                .andExpect(status().isForbidden());
    }
}
