package com.pebble.api.global.presentation.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApiResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void successResponseUsesDataWrapper() throws Exception {
        JsonNode json = objectMapper.readTree(
                objectMapper.writeValueAsBytes(ApiResponse.of(Map.of("id", "721389012345678901"))));

        assertThat(json.has("data")).isTrue();
        assertThat(json.at("/data/id").asText()).isEqualTo("721389012345678901");
    }

    @Test
    void errorResponseUsesCommonFieldsAndEmptyDetailsByDefault() throws Exception {
        var error = new ApiErrorResponse.ErrorBody("VALIDATION_ERROR", "요청 값을 확인해 주세요.", null, "trace-1");

        JsonNode json = objectMapper.readTree(
                objectMapper.writeValueAsBytes(new ApiErrorResponse(error)));

        assertThat(json.at("/error/code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(json.at("/error/message").asText()).isEqualTo("요청 값을 확인해 주세요.");
        assertThat(json.at("/error/traceId").asText()).isEqualTo("trace-1");
        assertThat(json.at("/error/details").isArray()).isTrue();
        assertThat(json.at("/error/details")).isEmpty();
    }

    @Test
    void errorResponsePreservesFieldDetails() throws Exception {
        var details = List.of(new ApiErrorResponse.FieldDetail("title", "최대 200자까지 입력할 수 있습니다."));
        var error = new ApiErrorResponse.ErrorBody("VALIDATION_ERROR", "요청 값을 확인해 주세요.", details, "trace-1");

        JsonNode json = objectMapper.readTree(
                objectMapper.writeValueAsBytes(new ApiErrorResponse(error)));

        assertThat(json.at("/error/details/0/field").asText()).isEqualTo("title");
        assertThat(json.at("/error/details/0/reason").asText()).isEqualTo("최대 200자까지 입력할 수 있습니다.");
    }
}
