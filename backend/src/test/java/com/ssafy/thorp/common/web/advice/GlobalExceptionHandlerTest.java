package com.ssafy.thorp.common.web.advice;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.common.config.SecurityConfig;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
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
import org.springframework.web.bind.annotation.PathVariable;
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
@Import({SecurityConfig.class, GlobalExceptionHandlerTest.ThrowingController.class})
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
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.error.message").value("name: 이름은 필수입니다"));
    }

    @Test
    @DisplayName("요청 검증 실패는 details.fields에 필드 오류 전부를 싣는다")
    void validationExceptionListsAllFieldErrors() throws Exception {
        mockMvc.perform(
                        post("/test/validation-many")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"\", \"code\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.error.details.fields.length()").value(2))
                .andExpect(
                        jsonPath("$.error.details.fields[*].field")
                                .value(containsInAnyOrder("name", "code")))
                .andExpect(
                        jsonPath("$.error.details.fields[?(@.field == 'code')].message")
                                .value(contains("코드는 필수입니다")));
    }

    @Test
    @DisplayName("도메인 불변식 위반(IllegalArgumentException)은 400으로 응답한다")
    void illegalArgumentException() throws Exception {
        mockMvc.perform(get("/test/illegal-argument"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(
                        jsonPath("$.error.message").value("startDate must not be after endDate"));
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
    @DisplayName("깨진 JSON 본문은 500이 아니라 400으로 응답한다")
    void malformedJsonBody() throws Exception {
        mockMvc.perform(
                        post("/test/validation")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("경로 변수 타입 불일치는 500이 아니라 400으로 응답한다")
    void pathVariableTypeMismatch() throws Exception {
        mockMvc.perform(get("/test/number/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("허용되지 않는 메서드는 500이 아니라 405로 응답한다")
    void methodNotAllowed() throws Exception {
        mockMvc.perform(delete("/test/business").with(csrf()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.success").value(false));
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

        @PostMapping("/validation-many")
        void validationMany(@Valid @RequestBody TwoFieldRequest request) {}

        @GetMapping("/number/{value}")
        void number(@PathVariable Long value) {}

        @GetMapping("/illegal-argument")
        void illegalArgument() {
            throw new IllegalArgumentException("startDate must not be after endDate");
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new IllegalStateException("internal detail that must not leak");
        }
    }

    record SampleRequest(@NotBlank(message = "이름은 필수입니다") String name) {}

    record TwoFieldRequest(
            @NotBlank(message = "이름은 필수입니다") String name,
            @NotBlank(message = "코드는 필수입니다") String code) {}
}
