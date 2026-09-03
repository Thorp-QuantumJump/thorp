package com.thorp.api.common.web.advice;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thorp.api.common.exception.BusinessException;
import com.thorp.api.common.exception.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 전역 예외 처리 응답 포맷 검증.
 *
 * <p>{@code @WebMvcTest} 슬라이스로 띄우므로 {@code JpaAuditingConfig}가 컨텍스트에서 제외되는지도 함께 확인된다.
 */
@WebMvcTest(controllers = GlobalExceptionHandlerTest.ThrowingController.class)
@Import(GlobalExceptionHandlerTest.ThrowingController.class)
class GlobalExceptionHandlerTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("BusinessException은 ErrorCode의 HTTP 상태와 코드로 응답한다")
    void businessException() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("백테스트 없음"));
    }

    @Test
    @DisplayName("요청 검증 실패는 400과 첫 필드 오류 메시지로 응답한다")
    void validationException() throws Exception {
        mockMvc.perform(
                        post("/test/validation")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.error.message").value("name: 이름은 필수입니다"));
    }

    @Test
    @DisplayName("존재하지 않는 경로는 500이 아니라 404로 응답한다")
    void unknownPath() throws Exception {
        mockMvc.perform(get("/test/no-such-path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("예상하지 못한 예외는 500과 고정 메시지로 응답하고 내부 메시지를 노출하지 않는다")
    void unexpectedException() throws Exception {
        mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(
                        jsonPath("$.error.message").value(ErrorCode.INTERNAL_ERROR.getMessage()));
    }

    @RestController
    @RequestMapping("/test")
    static class ThrowingController {

        @GetMapping("/business")
        void business() {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "백테스트 없음");
        }

        @PostMapping("/validation")
        void validation(@Valid @RequestBody SampleRequest request) {}

        @GetMapping("/unexpected")
        void unexpected() {
            throw new IllegalStateException("internal detail that must not leak");
        }
    }

    record SampleRequest(@NotBlank(message = "이름은 필수입니다") String name) {}
}
