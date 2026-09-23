package com.ssafy.thorp.account.presentation;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.common.config.SecurityConfig;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.common.web.advice.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = AuthErrorContractTest.ContractController.class)
@Import({
    SecurityConfig.class,
    GlobalExceptionHandler.class,
    AuthErrorContractTest.ContractController.class
})
class AuthErrorContractTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("인증 비즈니스 오류도 공통 실패 응답 형식을 사용한다")
    void authenticationBusinessErrorUsesCommonEnvelope() throws Exception {
        mockMvc.perform(get("/contract/auth-required"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.error.message").value("로그인이 필요합니다."));
    }

    @Test
    @DisplayName("CSRF 토큰 누락은 공통 실패 응답 형식의 403으로 응답한다")
    void missingCsrfTokenUsesCommonEnvelope() throws Exception {
        mockMvc.perform(post("/auth/contract/mutation"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("CSRF_TOKEN_INVALID"))
                .andExpect(jsonPath("$.error.message").value("요청 확인 정보가 만료되었습니다. 다시 시도해 주세요."));
    }

    @Test
    @DisplayName("예상하지 못한 인증 영역 오류는 내부 메시지를 숨긴다")
    void unexpectedErrorIsSanitized() throws Exception {
        mockMvc.perform(get("/contract/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(
                        jsonPath("$.error.message").value(ErrorCode.INTERNAL_ERROR.getMessage()));
    }

    @RestController
    static class ContractController {

        @GetMapping("/contract/auth-required")
        void authRequired() {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }

        @PostMapping("/auth/contract/mutation")
        void mutate() {}

        @GetMapping("/contract/unexpected")
        void unexpected() {
            throw new IllegalStateException("credential-like internal detail");
        }
    }
}
