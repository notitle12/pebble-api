package com.pebble.api.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pebble.api.support.AuthenticationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDisabledIntegrationTest extends AuthenticationTestSupport {
    @Autowired MockMvc mvc;

    @Test
    void defaultExecutionDoesNotExposeDocumentation() throws Exception {
        for (var path : new String[] {"/v3/api-docs", "/v3/api-docs/member", "/swagger-ui/index.html"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }
}
