package com.pebble.api.global.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(GlobalExceptionHandlerTest.TestConfig.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @TestConfiguration
    static class TestConfig {
        @RestController
        @RequestMapping("/test")
        static class TestController {

            @GetMapping("/success")
            public ApiResponse<String> success() {
                return ApiResponse.success("ok");
            }

            @GetMapping("/custom-exception")
            public ApiResponse<Void> customException() {
                throw new CustomException(ErrorCode.RESOURCE_NOT_FOUND);
            }

            @PostMapping("/validation")
            public ApiResponse<String> validation(@RequestBody @Valid TestRequest request) {
                return ApiResponse.success(request.getName());
            }

            @GetMapping("/unhandled-exception")
            public ApiResponse<Void> unhandledException() {
                throw new RuntimeException("Unexpected error");
            }

            @GetMapping("/type-mismatch")
            public ApiResponse<String> typeMismatch(@RequestParam("age") Integer age) {
                return ApiResponse.success(String.valueOf(age));
            }

            @GetMapping("/missing-param")
            public ApiResponse<String> missingParam(@RequestParam("requiredParam") String requiredParam) {
                return ApiResponse.success(requiredParam);
            }
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    static class TestRequest {
        @NotBlank(message = "이름은 필수 입력 항목입니다.")
        private String name;
    }

    @Test
    @DisplayName("정상 요청 시 ApiResponse 규격으로 200 OK가 반환된다")
    void success_api_response() throws Exception {
        mockMvc.perform(get("/test/success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("요청이 성공적으로 처리되었습니다."))
                .andExpect(jsonPath("$.data").value("ok"));
    }

    @Test
    @DisplayName("CustomException 발생 시 매핑된 ErrorCode의 상태코드와 ErrorResponse가 반환된다")
    void custom_exception_handling() throws Exception {
        mockMvc.perform(get("/test/custom-exception"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("C003"))
                .andExpect(jsonPath("$.message").value("요청한 리소스를 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("입력값 검증(Validation) 실패 시 400 상태코드와 상세 필드 에러가 반환된다")
    void validation_exception_handling() throws Exception {
        TestRequest invalidRequest = new TestRequest();
        invalidRequest.setName("");

        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("C001"))
                .andExpect(jsonPath("$.message").value("적절하지 않은 요청 값입니다."))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].reason").value("이름은 필수 입력 항목입니다."));
    }

    @Test
    @DisplayName("지원하지 않는 HTTP Method 요청 시 405 Method Not Allowed가 반환된다")
    void method_not_allowed_handling() throws Exception {
        mockMvc.perform(post("/test/success"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("C002"))
                .andExpect(jsonPath("$.message").value("지원하지 않는 HTTP 메서드입니다."));
    }

    @Test
    @DisplayName("기타 서버 예외 발생 시 500 상태코드와 기본 에러 메시지가 반환된다")
    void unhandled_exception_handling() throws Exception {
        mockMvc.perform(get("/test/unhandled-exception"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("C004"))
                .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."));
    }

    @Test
    @DisplayName("잘못된 JSON 형식 요청 시 400 Bad Request와 C001 에러가 반환된다")
    void http_message_not_readable_handling() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invalid_json\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("C001"))
                .andExpect(jsonPath("$.message").value("요청 본문의 형식이 올바르지 않거나 읽을 수 없습니다."));
    }

    @Test
    @DisplayName("파라미터 타입 불일치 시 400 Bad Request와 상세 필드 에러가 반환된다")
    void method_argument_type_mismatch_handling() throws Exception {
        mockMvc.perform(get("/test/type-mismatch")
                        .param("age", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("C001"))
                .andExpect(jsonPath("$.message").value("적절하지 않은 요청 값입니다."))
                .andExpect(jsonPath("$.errors[0].field").value("age"))
                .andExpect(jsonPath("$.errors[0].value").value("not-a-number"))
                .andExpect(jsonPath("$.errors[0].reason").value("요청 값의 타입이 올바르지 않습니다."));
    }

    @Test
    @DisplayName("필수 Request Parameter 누락 시 400 Bad Request와 상세 필드 에러가 반환된다")
    void missing_servlet_request_parameter_handling() throws Exception {
        mockMvc.perform(get("/test/missing-param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("C001"))
                .andExpect(jsonPath("$.message").value("적절하지 않은 요청 값입니다."))
                .andExpect(jsonPath("$.errors[0].field").value("requiredParam"))
                .andExpect(jsonPath("$.errors[0].reason").value("필수 요청 파라미터가 누락되었습니다."));
    }
}
