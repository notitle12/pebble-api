package com.pebble.api.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.support.AuthenticationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "api-docs"})
class OpenApiIntegrationTest extends AuthenticationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void memberDocumentContainsRealSchemasAndEndpointSecurity() throws Exception {
        var response = mvc.perform(get("/v3/api-docs/member")).andDo(result -> { if (result.getResolvedException() != null) throw result.getResolvedException(); }).andExpect(status().isOk()).andReturn();
        var json = mapper.readTree(response.getResponse().getContentAsString());
        var paths = json.path("paths");
        assertThat(paths.has("/api/v1/posts")).isTrue();
        assertThat(paths.path("/api/v1/posts").path("get").path("security").size()).isZero();
        assertThat(paths.path("/api/v1/posts").path("post").path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(paths.path("/api/v1/posts/{postId}").path("patch").path("x-pebble-access").asText()).isEqualTo("USER");
        assertThat(paths.has("/api/v1/admin/posts")).isFalse();
        assertThat(json.path("components").path("schemas").size()).isGreaterThan(20);
    }

    @Test
    void adminDocumentIsSeparatedAndIncludesRequiredRole() throws Exception {
        var response = mvc.perform(get("/v3/api-docs/admin")).andDo(result -> { if (result.getResolvedException() != null) throw result.getResolvedException(); }).andExpect(status().isOk()).andReturn();
        var paths = mapper.readTree(response.getResponse().getContentAsString()).path("paths");
        assertThat(paths.path("/api/v1/admin/admin-accounts").path("post").path("x-pebble-access").asText()).isEqualTo("MASTER");
        assertThat(paths.has("/api/v1/posts")).isFalse();
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
