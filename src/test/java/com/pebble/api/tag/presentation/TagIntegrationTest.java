package com.pebble.api.tag.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pebble.api.auth.application.oauth.OAuthProviderClient;
import com.pebble.api.support.AuthenticationTestSupport;
import com.pebble.api.tag.domain.Tag;
import com.pebble.api.tag.domain.TagStatus;
import com.pebble.api.tag.infrastructure.persistence.TagRepository;
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
class TagIntegrationTest extends AuthenticationTestSupport {

    private static final String PATH = "/api/v1/tags";
    private static final String ORIGIN = "http://localhost:3000";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TagRepository tags;

    @MockitoBean(name = "naverOAuthClient")
    private OAuthProviderClient naverOAuthGateway;

    @BeforeEach
    void clearTagsWithinRollbackTransaction() {
        tags.deleteAllInBatch();
    }

    @Test
    void anonymousGuestCanReadAnEmptyList() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void returnsActiveTagsWithStableOrderAndStringIds() throws Exception {
        tags.saveAndFlush(new Tag("Later", "test-later", 2, TagStatus.ACTIVE));
        Tag first = tags.saveAndFlush(new Tag("First", "test-first", 1, TagStatus.ACTIVE));
        Tag second = tags.saveAndFlush(new Tag("Second", "test-second", 1, TagStatus.ACTIVE));
        tags.saveAndFlush(new Tag("Inactive", "test-inactive", 0, TagStatus.INACTIVE));
        Tag smaller = first.getId() < second.getId() ? first : second;
        Tag larger = smaller == first ? second : first;

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
    void permitsFrontendGetPreflightButNotOtherOriginsOrMethods() throws Exception {
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
    }
}
