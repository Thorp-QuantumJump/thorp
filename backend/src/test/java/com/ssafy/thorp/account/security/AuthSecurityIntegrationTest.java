package com.ssafy.thorp.account.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.common.config.SecurityConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = AuthSecurityIntegrationTest.SecurityProbeController.class)
@Import({SecurityConfig.class, AuthSecurityIntegrationTest.SecurityProbeController.class})
class AuthSecurityIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private SessionAuthenticationStrategy sessionAuthenticationStrategy;

    @Test
    @DisplayName("시장 정보 같은 공개 경로는 로그인 없이 이용할 수 있다")
    void publicRouteRemainsAccessible() throws Exception {
        mockMvc.perform(get("/markets/security-probe")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("현재 사용자 경로는 비로그인 요청에 JSON 401을 반환한다")
    void currentUserRouteRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("CSRF 준비 요청은 Axios 표준 이름과 속성의 쿠키를 만든다")
    void csrfBootstrapCreatesReadableCookie() throws Exception {
        mockMvc.perform(get("/auth/csrf"))
                .andExpect(status().isNoContent())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false))
                .andExpect(cookie().path("XSRF-TOKEN", "/"))
                .andExpect(
                        result ->
                                assertThat(
                                                result.getResponse()
                                                        .getCookie("XSRF-TOKEN")
                                                        .getAttribute("SameSite"))
                                        .isEqualTo("Lax"));
    }

    @Test
    @DisplayName("상태 변경 요청은 유효한 CSRF 토큰이 있어야 통과한다")
    void mutationRequiresCsrfToken() throws Exception {
        mockMvc.perform(post("/backtests/security-probe"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CSRF_TOKEN_INVALID"));

        mockMvc.perform(post("/backtests/security-probe").with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("인증 성공 시 기존 익명 세션 식별자를 교체한다")
    void authenticationRotatesSessionId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String anonymousSessionId = request.getSession(true).getId();

        sessionAuthenticationStrategy.onAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        "member@example.com", "ignored", java.util.List.of()),
                request,
                response);

        assertThat(request.getSession(false)).isNotNull();
        assertThat(request.getSession(false).getId()).isNotEqualTo(anonymousSessionId);
    }

    @RestController
    static class SecurityProbeController {

        @GetMapping("/markets/security-probe")
        String publicRoute() {
            return "ok";
        }

        @GetMapping("/auth/csrf")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void csrf(CsrfToken csrfToken) {
            csrfToken.getToken();
        }

        @GetMapping("/auth/me")
        String currentUser() {
            return "authenticated";
        }

        @PostMapping("/backtests/security-probe")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void mutate(HttpServletRequest request) {}
    }
}
