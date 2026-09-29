package com.ssafy.thorp.account.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.account.application.repository.UserRepository;
import com.ssafy.thorp.account.domain.model.User;
import com.ssafy.thorp.account.domain.model.UserStatus;
import com.ssafy.thorp.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 회원정보 수정({@code PATCH /accounts/me})을 브라우저와 같은 순서로 부른다 — CSRF 쿠키를 받고, 로그인하고, 로그인 응답이 갈아 준 CSRF 쿠키와
 * 세션 쿠키로 요청한다.
 */
class AccountProfileIntegrationTest extends IntegrationTestSupport {

    private static final String EMAIL = "profile-it-member@example.com";
    private static final String PASSWORD = "password123";
    private static final String NICKNAME = "옛닉네임";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from spring_session");
        jdbcTemplate.update("delete from users where email like 'profile-it-%'");
        userId =
                userRepository
                        .save(
                                new User(
                                        null,
                                        EMAIL,
                                        passwordEncoder.encode(PASSWORD),
                                        NICKNAME,
                                        "01055556666",
                                        null,
                                        UserStatus.ACTIVE,
                                        false,
                                        false,
                                        false,
                                        null,
                                        null,
                                        null,
                                        null))
                        .getId();
    }

    @Test
    @DisplayName("로그인한 사용자는 닉네임을 바꾸고, 현재 사용자 조회에도 바뀐 닉네임이 보인다")
    void changesNicknameAndCurrentUserSeesIt() throws Exception {
        Cookie[] cookies = login();

        mockMvc.perform(changeNickname(cookies, "  새닉네임  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data.userId").value(userId))
                .andExpect(jsonPath("$.data.email").value(EMAIL))
                .andExpect(jsonPath("$.data.nickname").value("새닉네임"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.phoneNumber").doesNotExist());

        mockMvc.perform(get("/auth/me").cookie(cookies))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("새닉네임"));
        assertThat(storedNickname()).isEqualTo("새닉네임");
    }

    @Test
    @DisplayName("앞뒤 공백을 뗀 뒤 2자에 못 미치는 닉네임은 400이고 저장하지 않는다")
    void rejectsTooShortNicknameAfterStripping() throws Exception {
        Cookie[] cookies = login();

        mockMvc.perform(changeNickname(cookies, "   가   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        assertThat(storedNickname()).isEqualTo(NICKNAME);
    }

    @Test
    @DisplayName("20자를 넘는 닉네임은 400이고 저장하지 않는다")
    void rejectsTooLongNickname() throws Exception {
        Cookie[] cookies = login();

        mockMvc.perform(changeNickname(cookies, "가".repeat(21)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        assertThat(storedNickname()).isEqualTo(NICKNAME);
    }

    @Test
    @DisplayName("세션 없이 닉네임을 바꾸려 하면 공통 형식의 401이다")
    void rejectsAnonymousRequest() throws Exception {
        Cookie csrf = csrfCookie();

        mockMvc.perform(
                        patch("/accounts/me")
                                .cookie(csrf)
                                .header("X-XSRF-TOKEN", csrf.getValue())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("새닉네임")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
        assertThat(storedNickname()).isEqualTo(NICKNAME);
    }

    @Test
    @DisplayName("CSRF 헤더 없이 보내면 로그인 상태여도 403이다")
    void rejectsRequestWithoutCsrfHeader() throws Exception {
        Cookie[] cookies = login();

        mockMvc.perform(
                        patch("/accounts/me")
                                .cookie(cookies)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("새닉네임")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CSRF_TOKEN_INVALID"));
        assertThat(storedNickname()).isEqualTo(NICKNAME);
    }

    /** 로그인하고 세션 쿠키와 새 CSRF 쿠키를 돌려준다. 로그인은 CSRF 토큰을 갈므로 전의 쿠키는 쓰지 않는다. */
    private Cookie[] login() throws Exception {
        Cookie csrf = csrfCookie();
        MockHttpServletResponse response =
                mockMvc.perform(
                                post("/auth/sessions")
                                        .cookie(csrf)
                                        .header("X-XSRF-TOKEN", csrf.getValue())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"email\":\"%s\",\"password\":\"%s\"}"
                                                        .formatted(EMAIL, PASSWORD)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        Cookie session = response.getCookie("THORP_SESSION");
        Cookie rotatedCsrf = response.getCookie("XSRF-TOKEN");
        assertThat(session).isNotNull();
        assertThat(rotatedCsrf).isNotNull();
        return new Cookie[] {session, rotatedCsrf};
    }

    private MockHttpServletRequestBuilder changeNickname(Cookie[] cookies, String nickname) {
        return patch("/accounts/me")
                .cookie(cookies)
                .header("X-XSRF-TOKEN", cookies[1].getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(nickname));
    }

    private Cookie csrfCookie() throws Exception {
        Cookie csrf =
                mockMvc.perform(get("/auth/csrf"))
                        .andReturn()
                        .getResponse()
                        .getCookie("XSRF-TOKEN");
        assertThat(csrf).isNotNull();
        return csrf;
    }

    private static String body(String nickname) {
        return "{\"nickname\":\"%s\"}".formatted(nickname);
    }

    private String storedNickname() {
        return jdbcTemplate.queryForObject(
                "select nickname from users where id = ?", String.class, userId);
    }
}
