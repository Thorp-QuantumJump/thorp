package com.ssafy.thorp.account.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 프록시 뒤에서 인증 쿠키에 {@code Secure}가 붙는지 확인한다.
 *
 * <p>운영은 nginx가 TLS를 끝내고 <b>평문 HTTP</b>로 백엔드에 넘긴다. 그래서 톰캣이 보는 요청은 그냥 http이고, 아무 설정이 없으면 {@code
 * request.isSecure()}가 false다. 세션 쿠키는 {@code server.servlet.session.cookie.secure=true}로 못 박아 두었지만
 * <b>XSRF-TOKEN 쿠키는 그 값을 요청이 HTTPS인지 보고 정한다</b>({@code CookieCsrfTokenRepository}). 즉 {@code
 * forward-headers-strategy}가 없으면 운영에서만 조용히 {@code Secure} 없는 CSRF 쿠키가 나간다.
 *
 * <p>이 테스트가 운영과 같은 두 설정을 켜고 {@code X-Forwarded-Proto}를 직접 보내는 이유가 그것이다 — 배포해 봐야 드러나는 종류의 사고라서 여기서
 * 잡는다. 실제 값이 프로필에 들어 있는지는 {@code ApplicationProfileConfigTest}가 따로 지킨다.
 */
// 운영(application-prod.yml)과 같은 구성. 모든 테스트의 쿠키 설정을 바꾸지 않도록 기반에 넣지 않고, 이 테스트만 컨텍스트를 따로 만든다.
@TestPropertySource(
        properties = {
            "server.forward-headers-strategy=framework",
            "server.servlet.session.cookie.secure=true"
        })
class ProxySecureCookieIntegrationTest extends IntegrationTestSupport {

    private static final String FORWARDED_PROTO = "X-Forwarded-Proto";

    private static final String EMAIL = "proxy-it-member@example.com";
    private static final String PASSWORD = "password123";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from spring_session");
        jdbcTemplate.update("delete from users where email like 'proxy-it-%'");
        saveUser();
    }

    @Test
    @DisplayName("HTTPS 프록시 뒤에서 발급된 CSRF 쿠키에는 Secure가 붙는다")
    void csrfCookieIsSecureBehindAnHttpsProxy() throws Exception {
        Cookie csrf = csrfToken(true);

        assertThat(csrf.getSecure()).isTrue();
        // Secure가 붙어도 SPA는 읽을 수 있어야 한다 — 읽어서 헤더로 되돌려 보내는 것이 이 방식의 전부다.
        assertThat(csrf.isHttpOnly()).isFalse();
    }

    @Test
    @DisplayName("HTTPS 프록시 뒤에서 발급된 세션 쿠키에는 Secure와 HttpOnly가 함께 붙는다")
    void sessionCookieIsSecureBehindAnHttpsProxy() throws Exception {
        Cookie csrf = csrfToken(true);

        Cookie session =
                mockMvc.perform(
                                post("/auth/sessions")
                                        .header(FORWARDED_PROTO, "https")
                                        .cookie(csrf)
                                        .header("X-XSRF-TOKEN", csrf.getValue())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"email\":\"%s\",\"password\":\"%s\"}"
                                                        .formatted(EMAIL, PASSWORD)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getCookie("THORP_SESSION");

        assertThat(session).isNotNull();
        assertThat(session.getSecure()).isTrue();
        assertThat(session.isHttpOnly()).isTrue();
    }

    @Test
    @DisplayName("전달 헤더가 없는 평문 요청의 CSRF 쿠키에는 Secure를 붙이지 않는다")
    void csrfCookieIsNotSecureOnPlainHttp() throws Exception {
        // 붙이면 브라우저가 http 로컬 개발 환경에서 쿠키를 버려 로그인 자체가 되지 않는다.
        // Secure 판정이 프록시 헤더에서 온다는 것을 확인하는 반대편 근거이기도 하다.
        assertThat(csrfToken(false).getSecure()).isFalse();
    }

    private Cookie csrfToken(boolean behindHttpsProxy) throws Exception {
        var request = get("/auth/csrf");
        if (behindHttpsProxy) {
            request = request.header(FORWARDED_PROTO, "https");
        }

        Cookie csrf =
                mockMvc.perform(request)
                        .andExpect(status().isNoContent())
                        .andReturn()
                        .getResponse()
                        .getCookie("XSRF-TOKEN");
        assertThat(csrf).isNotNull();
        return csrf;
    }

    private User saveUser() {
        return userRepository.save(
                new User(
                        null,
                        EMAIL,
                        passwordEncoder.encode(PASSWORD),
                        "프록시회원",
                        "01055550001",
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
