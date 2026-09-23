package com.ssafy.thorp.account.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.account.application.repository.UserRepository;
import com.ssafy.thorp.account.domain.model.User;
import com.ssafy.thorp.account.domain.model.UserStatus;
import com.ssafy.thorp.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 로그인·현재 사용자 조회·CSRF 발급을 실제 PostgreSQL 세션 저장소 위에서 호출한다.
 *
 * <p>브라우저가 하는 일을 그대로 따라 한다 — {@code GET /auth/csrf}로 받은 {@code XSRF-TOKEN} 쿠키를 {@code X-XSRF-TOKEN}
 * 헤더로 돌려보내고, 로그인 응답의 {@code THORP_SESSION} 쿠키를 다음 요청에 실어 보낸다. 토큰과 세션을 테스트 도우미로 주입하면 SPA가 실제로 쓰는 이
 * 경로가 검증되지 않는다.
 *
 * <p>브로커 컨테이너는 띄우지 않는다({@code BacktestResultApiIT}와 같은 방식). DB는 PostgreSQL 컨테이너다.
 */
class AuthenticationControllerIntegrationTest extends IntegrationTestSupport {

    private static final String EMAIL = "auth-it-member@example.com";
    private static final String PASSWORD = "password123";
    private static final String NICKNAME = "인증회원";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from spring_session");
        jdbcTemplate.update("delete from users where email like 'auth-it-%'");
        userId = saveUser(EMAIL, UserStatus.ACTIVE).getId();
    }

    @Test
    @DisplayName("GET /auth/csrf는 SPA가 읽을 수 있는 XSRF-TOKEN 쿠키를 발급한다")
    void csrfBootstrapIssuesReadableToken() throws Exception {
        mockMvc.perform(get("/auth/csrf"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false))
                .andExpect(cookie().path("XSRF-TOKEN", "/"));
    }

    @Test
    @DisplayName("POST /auth/sessions는 세션을 PostgreSQL에 만들고 민감정보 없는 사용자만 돌려준다")
    void loginCreatesPersistentSessionAndReturnsAuthenticatedUser() throws Exception {
        MvcResult result =
                mockMvc.perform(login(EMAIL, PASSWORD))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.success").value(true))
                        .andExpect(jsonPath("$.error").value(nullValue()))
                        .andExpect(jsonPath("$.data.userId").value(userId))
                        .andExpect(jsonPath("$.data.email").value(EMAIL))
                        .andExpect(jsonPath("$.data.nickname").value(NICKNAME))
                        .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                        .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                        .andExpect(jsonPath("$.data.password").doesNotExist())
                        .andExpect(jsonPath("$.data.phoneNumber").doesNotExist())
                        .andReturn();

        Cookie session = result.getResponse().getCookie("THORP_SESSION");
        assertThat(session).isNotNull();
        assertThat(session.isHttpOnly()).isTrue();
        assertThat(session.getValue()).isNotBlank();

        assertThat(
                        jdbcTemplate.queryForObject(
                                "select count(*) from spring_session", Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "select principal_name from spring_session", String.class))
                .isEqualTo(String.valueOf(userId));
        assertThat(
                        jdbcTemplate.queryForObject(
                                "select count(*) from spring_session_attributes"
                                        + " where attribute_name = ?",
                                Integer.class,
                                "SPRING_SECURITY_CONTEXT"))
                .isEqualTo(1);
        assertThat(lastLoginAt()).isNotNull();
    }

    @Test
    @DisplayName("GET /auth/me는 세션 쿠키만으로 같은 사용자를 복원한다")
    void currentUserIsRestoredFromTheSessionCookie() throws Exception {
        Cookie session = loginAndGetSessionCookie();

        mockMvc.perform(get("/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data.userId").value(userId))
                .andExpect(jsonPath("$.data.email").value(EMAIL))
                .andExpect(jsonPath("$.data.nickname").value(NICKNAME))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("세션 없이 현재 사용자를 조회하면 공통 형식의 401을 돌려준다")
    void currentUserWithoutSessionIsUnauthorized() throws Exception {
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("로그인 성공은 그 전에 쓰던 세션 식별자를 그대로 쓰지 않는다")
    void loginRotatesTheSessionIdentifier() throws Exception {
        Cookie first = loginAndGetSessionCookie();

        MvcResult second =
                mockMvc.perform(login(EMAIL, PASSWORD).cookie(first))
                        .andExpect(status().isOk())
                        .andReturn();

        Cookie rotated = second.getResponse().getCookie("THORP_SESSION");
        assertThat(rotated).isNotNull();
        assertThat(rotated.getValue()).isNotEqualTo(first.getValue());

        mockMvc.perform(get("/auth/me").cookie(first)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/auth/me").cookie(rotated)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("틀린 비밀번호와 비활성 계정은 구분되지 않는 같은 401로 끝나고 세션을 만들지 않는다")
    void wrongPasswordAndInactiveAccountShareTheSameRejection() throws Exception {
        saveUser("auth-it-suspended@example.com", UserStatus.SUSPENDED);

        String wrongPassword = rejectionBody(login(EMAIL, "wrong-password"));
        String inactiveAccount = rejectionBody(login("auth-it-suspended@example.com", PASSWORD));
        String unknownEmail = rejectionBody(login("auth-it-nobody@example.com", PASSWORD));

        assertThat(inactiveAccount).isEqualTo(wrongPassword);
        assertThat(unknownEmail).isEqualTo(wrongPassword);

        assertThat(
                        jdbcTemplate.queryForObject(
                                "select count(*) from spring_session", Integer.class))
                .isZero();
        assertThat(lastLoginAt()).isNull();
    }

    @Test
    @DisplayName("CSRF 토큰 없는 로그인 요청은 인증을 시도하지 않고 403으로 끝난다")
    void loginWithoutCsrfTokenIsRejected() throws Exception {
        mockMvc.perform(
                        post("/auth/sessions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(credentials(EMAIL, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CSRF_TOKEN_INVALID"));

        assertThat(
                        jdbcTemplate.queryForObject(
                                "select count(*) from spring_session", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("앞뒤 공백과 대문자가 섞인 이메일도 정규형으로 같은 계정에 로그인한다")
    void loginNormalizesTheSubmittedEmailBeforeValidatingIt() throws Exception {
        // 정규화가 검증 뒤로 밀리면 앞뒤 공백 때문에 @Email이 먼저 걸려 400이 된다(커밋 7171ea62).
        mockMvc.perform(login("  AUTH-IT-Member@Example.COM  ", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(userId))
                .andExpect(jsonPath("$.data.email").value(EMAIL));
    }

    @Test
    @DisplayName("이메일 형식이 아닌 로그인 요청은 자격 증명 오류와 구분되는 400으로 끝난다")
    void malformedLoginRequestIsInvalidInput() throws Exception {
        mockMvc.perform(login("not-an-email", PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    /** 브라우저처럼 CSRF 쿠키를 먼저 받아 헤더로 돌려보내는 로그인 요청을 만든다. */
    private MockHttpServletRequestBuilder login(String email, String password) throws Exception {
        Cookie csrf =
                mockMvc.perform(get("/auth/csrf"))
                        .andReturn()
                        .getResponse()
                        .getCookie("XSRF-TOKEN");
        assertThat(csrf).isNotNull();

        return post("/auth/sessions")
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email, password));
    }

    private Cookie loginAndGetSessionCookie() throws Exception {
        Cookie session =
                mockMvc.perform(login(EMAIL, PASSWORD))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getCookie("THORP_SESSION");
        assertThat(session).isNotNull();
        return session;
    }

    private String rejectionBody(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static String credentials(String email, String password) {
        return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
    }

    private Instant lastLoginAt() {
        return jdbcTemplate.queryForObject(
                "select last_login_at from users where id = ?", Instant.class, userId);
    }

    private User saveUser(String email, UserStatus status) {
        return userRepository.save(
                new User(
                        null,
                        email,
                        passwordEncoder.encode(PASSWORD),
                        NICKNAME,
                        status == UserStatus.ACTIVE ? "01012345678" : "01098765432",
                        null,
                        status,
                        false,
                        false,
                        false,
                        null,
                        null,
                        null,
                        null));
    }
}
