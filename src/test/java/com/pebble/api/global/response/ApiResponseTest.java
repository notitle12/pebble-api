package com.pebble.api.global.response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    @DisplayName("성공 응답 생성 시 기본 메시지와 데이터가 올바르게 설정된다")
    void success_with_data() {
        String testData = "test-data";
        ApiResponse<String> response = ApiResponse.success(testData);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getMessage()).isEqualTo("요청이 성공적으로 처리되었습니다.");
        assertThat(response.getData()).isEqualTo(testData);
    }

    @Test
    @DisplayName("커스텀 메시지를 포함한 성공 응답 생성이 올바르게 동작한다")
    void success_with_custom_message() {
        String testData = "test-data";
        String customMessage = "조회에 성공했습니다.";
        ApiResponse<String> response = ApiResponse.success(customMessage, testData);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getMessage()).isEqualTo(customMessage);
        assertThat(response.getData()).isEqualTo(testData);
    }

    @Test
    @DisplayName("데이터가 없는 성공 응답 생성이 올바르게 동작한다")
    void success_without_data() {
        ApiResponse<Void> response = ApiResponse.success();

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getMessage()).isEqualTo("요청이 성공적으로 처리되었습니다.");
        assertThat(response.getData()).isNull();
    }
}
