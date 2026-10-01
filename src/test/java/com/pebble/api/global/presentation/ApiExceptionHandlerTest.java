package com.pebble.api.global.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pebble.api.auth.domain.AuthError;
import com.pebble.api.auth.domain.AuthException;
import com.pebble.api.global.presentation.trace.TraceIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class ApiExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void configureMvc() {
        mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new ApiExceptionHandler()).addFilters(new TraceIdFilter()).build();
    }

    @ParameterizedTest
    @EnumSource(AuthError.class)
    void handlesFeatureErrorUsingCommonAdvice(AuthError error) throws Exception {
        mvc.perform(post("/test/error").contentType(MediaType.APPLICATION_JSON).content('"' + error.name() + '"'))
                .andExpect(status().is(error.status().value()))
                .andExpect(jsonPath("$.error.code").value(error.code()))
                .andExpect(jsonPath("$.error.message").value(error.message()))
                .andExpect(jsonPath("$.error.details").isArray())
                .andExpect(jsonPath("$.error.traceId").isNotEmpty());
    }

    @Test
    void retainsValidationErrorContract() throws Exception {
        mvc.perform(post("/test/validate").contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("value"))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty());
    }

    @Test
    void retainsUnreadableRequestContract() throws Exception {
        mvc.perform(post("/test/validate").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void hidesUnexpectedExceptionDetails() throws Exception {
        mvc.perform(post("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.message").value("요청을 처리하지 못했습니다."));
    }

    @RestController
    static class TestController {
        @PostMapping("/test/error")
        void error(@RequestBody AuthError error) {
            throw new AuthException(error);
        }

        @PostMapping("/test/validate")
        void validate(@Valid @RequestBody TestRequest request) {
        }

        @PostMapping("/test/unexpected")
        void unexpected() {
            throw new IllegalStateException("internal details");
        }
    }

    record TestRequest(@NotBlank String value) {
    }
}
