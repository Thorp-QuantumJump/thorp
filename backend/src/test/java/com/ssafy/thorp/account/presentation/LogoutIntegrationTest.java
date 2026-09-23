package com.ssafy.thorp.account.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 로그아웃({@code DELETE /auth/sessions/current})을 실제 PostgreSQL 세션 저장소 위에서 호출한다.
 *
 * <p>여기서 확인하려는 것은 "화면에서 사라졌다"가 아니라 <b>서버 세션이 정말 없어졌다</b>는 것이다. 응답만 204로 만들고 세션 행을 남겨 두면, 쿠키를 보관한
 * 누군가가 로그아웃한 사용자로 계속 요청할 수 있다. 그래서 매번 {@code spring_session} 행과 {@code /auth/me} 재요청으로 확인한다.
 *
 * <p>브라우저가 하는 일을 그대로 따라 한다 — {@code GET /auth/csrf}로 받은 쿠키를 헤더로 돌려보내고 로그인 응답의 세션 쿠키를 다음 요청에 싣는다.
 */
class LogoutIntegrationTest extends IntegrationTestSupport {

    private static final String EMAIL = "logout-it-member@example.com";
    private static final String OTHER_EMAIL = "logout-it-other@example.com";
    private static final String PASSWORD = "password123";
    private static final String NICKNAME = "로그아웃회원";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from spring_session");
        jdbcTemplate.update("delete from users where email like 'logout-it-%'");
        // 전화번호도 고유 제약이라 다른 테스트 클래스가 남긴 행과 겹치지 않는 대역을 쓴다.
        saveUser(EMAIL, "01044440001");
    }

    @Test
    @DisplayName("로그아웃은 204로 끝나고 서버 세션과 세션 쿠키를 함께 없앤다")
    void logoutEndsTheCurrentSession() throws Exception {
        Cookie session = loginAndGetSessionCookie();
        assertThat(sessionCount()).isEqualTo(1);

        MvcResult result =
                mockMvc.perform(logout(session))
                        .andExpect(status().isNoContent())
                        .andExpect(content().string(""))
                        .andReturn();

        assertThat(sessionCount()).isZero();

        Cookie expired = result.getResponse().getCookie("THORP_SESSION");
        assertThat(expired).isNotNull();
        assertThat(expired.getMaxAge()).isZero();
        assertThat(expired.getValue()).isEmpty();
    }

    @Test
    @DisplayName("로그아웃한 세션 쿠키로는 현재 사용자를 더 이상 조회할 수 없다")
    void currentUserIsUnauthorizedAfterLogout() throws Exception {
        Cookie session = loginAndGetSessionCookie();
        mockMvc.perform(get("/auth/me").cookie(session)).andExpect(status().isOk());

        mockMvc.perform(logout(session)).andExpect(status().isNoContent());

        mockMvc.perform(get("/auth/me").cookie(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("비로그인 상태의 로그아웃도 같은 204로 끝난다")
    void anonymousLogoutIsAlsoNoContent() throws Exception {
        mockMvc.perform(logout(null))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(sessionCount()).isZero();
    }

    @Test
    @DisplayName("같은 세션 쿠키로 로그아웃을 되풀이해도 결과가 달라지지 않는다")
    void logoutIsIdempotent() throws Exception {
        // 네트워크 오류로 응답을 확인하지 못한 FE는 같은 요청을 다시 보낸다(FR-009).
        // 두 번째 요청이 404나 401이 되면 FE는 '로그아웃 미확인'에서 빠져나올 방법이 없다.
        Cookie session = loginAndGetSessionCookie();

        mockMvc.perform(logout(session)).andExpect(status().isNoContent());
        mockMvc.perform(logout(session)).andExpect(status().isNoContent());
        mockMvc.perform(logout(session)).andExpect(status().isNoContent());

        assertThat(sessionCount()).isZero();
    }

    @Test
    @DisplayName("로그아웃은 요청한 세션만 끝내고 같은 계정의 다른 세션은 남긴다")
    void logoutOnlyEndsTheRequestingSession() throws Exception {
        Cookie phone = loginAndGetSessionCookie();
        Cookie laptop = loginAndGetSessionCookie();
        assertThat(sessionCount()).isEqualTo(2);

        mockMvc.perform(logout(phone)).andExpect(status().isNoContent());

        assertThat(sessionCount()).isEqualTo(1);
        mockMvc.perform(get("/auth/me").cookie(phone)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/auth/me").cookie(laptop)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("다른 사용자의 로그아웃은 내 세션에 영향을 주지 않는다")
    void logoutDoesNotTouchOtherUsersSessions() throws Exception {
        saveUser(OTHER_EMAIL, "01044440002");
        Cookie mine = loginAndGetSessionCookie(EMAIL);
        Cookie theirs = loginAndGetSessionCookie(OTHER_EMAIL);

        mockMvc.perform(logout(theirs)).andExpect(status().isNoContent());

        mockMvc.perform(get("/auth/me").cookie(mine))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(EMAIL));
    }

    @Test
    @DisplayName("CSRF 토큰 없는 로그아웃은 403으로 끝나고 세션을 남겨 둔다")
    void logoutWithoutCsrfTokenIsRejected() throws Exception {
        Cookie session = loginAndGetSessionCookie();

        mockMvc.perform(delete("/auth/sessions/current").cookie(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CSRF_TOKEN_INVALID"));

        assertThat(sessionCount()).isEqualTo(1);
        mockMvc.perform(get("/auth/me").cookie(session)).andExpect(status().isOk());
    }

    /** 브라우저처럼 CSRF 쿠키를 먼저 받아 헤더로 돌려보내는 로그아웃 요청을 만든다. 세션 쿠키가 {@code null}이면 비로그인 요청이다. */
    private org.springframework.test.web.servlet.RequestBuilder logout(Cookie session)
            throws Exception {
        Cookie csrf = freshCsrfToken();
        var request =
                delete("/auth/sessions/current")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue());
        return session == null ? request : request.cookie(session);
    }

    private Cookie freshCsrfToken() throws Exception {
        Cookie csrf =
                mockMvc.perform(get("/auth/csrf"))
                        .andReturn()
                        .getResponse()
                        .getCookie("XSRF-TOKEN");
        assertThat(csrf).isNotNull();
        return csrf;
    }

    private Cookie loginAndGetSessionCookie() throws Exception {
        return loginAndGetSessionCookie(EMAIL);
    }

    private Cookie loginAndGetSessionCookie(String email) throws Exception {
        Cookie csrf = freshCsrfToken();
        Cookie session =
                mockMvc.perform(
                                post("/auth/sessions")
                                        .cookie(csrf)
                                        .header("X-XSRF-TOKEN", csrf.getValue())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"email\":\"%s\",\"password\":\"%s\"}"
                                                        .formatted(email, PASSWORD)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getCookie("THORP_SESSION");
        assertThat(session).isNotNull();
        return session;
    }

    private Integer sessionCount() {
        return jdbcTemplate.queryForObject("select count(*) from spring_session", Integer.class);
    }

    private User saveUser(String email, String phoneNumber) {
        return userRepository.save(
                new User(
                        null,
                        email,
                        passwordEncoder.encode(PASSWORD),
                        NICKNAME,
                        phoneNumber,
                        null,
                        UserStatus.ACTIVE,
                        false,
                        false,
                        false,
                        null,
                        null,
                        null,
                        null));
    }
}
