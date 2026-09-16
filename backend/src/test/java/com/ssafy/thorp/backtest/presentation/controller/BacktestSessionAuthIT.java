package com.ssafy.thorp.backtest.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ssafy.thorp.backtest.application.port.BacktestJobPublisher;
import com.ssafy.thorp.support.PostgresTestSupport;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 접수가 <b>진짜 로그인 세션</b>으로 되는지 본다(spec 004 T017 완료 기준).
 *
 * <p>다른 테스트는 {@code Requesters.as(id)}로 인증 주체를 직접 꽂는다 — 빠르지만 로그인 응답이 만든 세션 쿠키가 다음 요청에서 복원되는지는 지나치지
 * 않는다. 여기서만 {@code POST /auth/sessions → THORP_SESSION 쿠키 → POST /backtests}를 실제로 밟는다.
 *
 * <p>CSRF는 {@code csrf()} 후처리기에 맡긴다. 이 테스트의 관심사가 아니고(014 {@code
 * ProxySecureCookieIntegrationTest}·{@code AuthenticationControllerIntegrationTest}가 쿠키 흐름을 본다), 그
 * 후처리기가 공유 컨텍스트의 {@code CsrfFilter} 저장소를 테스트용으로 갈아 끼우기 때문에 같은 실행 안의 형제 테스트와 섞이면 쿠키 발급이 건너뛰어진다 — 실제로
 * 단독 실행은 통과하고 묶어 돌리면 깨졌다.
 *
 * <p>같이 확인하는 것: <b>폐기한 {@code X-User-Id} 헤더가 정말 아무 힘이 없는지</b>. 세션 사용자와 다른 값을 실어 보내도 저장되는 요청자는 세션
 * 사용자여야 한다.
 */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "thorp.backtest.republish.enabled=false",
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
@AutoConfigureMockMvc
class BacktestSessionAuthIT extends PostgresTestSupport {

    private static final Path REQUEST_FIXTURE =
            Path.of("..", "docs", "specs", "backtest-api", "examples", "backtest-request.json");
    private static final String PASSWORD = "Password!1";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;

    /** 브로커는 띄우지 않는다. 발행은 커밋 뒤라 접수 응답에 영향을 주지 않는다. */
    @MockitoBean private BacktestJobPublisher publisher;

    private long newUser(String email) {
        jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, nickname, phone_number, status,"
                        + " paper_ranking_enabled, live_ranking_enabled, ranking_display_nickname,"
                        + " created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'ACTIVE', false, false, false, NOW(), NOW())",
                email,
                passwordEncoder.encode(PASSWORD),
                "u" + Math.abs(email.hashCode()),
                Long.toString(System.nanoTime() % 1_000_000_000L));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    /** 같은 본문은 같은 동일 버전 키를 만든다. 컨테이너를 공유하므로 시작 금액으로 흔든다. */
    private String uniqueBody() throws Exception {
        long unique = 10_000_000L + (System.nanoTime() % 1_000_000L);
        return Files.readString(REQUEST_FIXTURE).replace("10000000.00", unique + ".00");
    }

    @Test
    @DisplayName("로그인 응답의 세션 쿠키로 접수되고, 다른 사용자의 X-User-Id 를 실어도 무시된다")
    void acceptsWithLoginSessionAndIgnoresLegacyHeader() throws Exception {
        String email = "session-" + System.nanoTime() + "@example.com";
        long sessionUser = newUser(email);
        long otherUser = newUser("other-" + System.nanoTime() + "@example.com");

        MvcResult loginResult =
                mockMvc.perform(
                                post("/auth/sessions")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"email": "%s", "password": "%s"}
                                                """
                                                        .formatted(email, PASSWORD)))
                        .andExpect(status().isOk())
                        .andReturn();

        // 로그인이 세션 식별자를 갈아 끼우고(014 FR-018) 그 쿠키로 다음 요청의 주체가 복원되는지가 이 테스트의 전부다.
        Cookie session = loginResult.getResponse().getCookie("THORP_SESSION");
        assertThat(session).isNotNull();

        String response =
                mockMvc.perform(
                                post("/backtests")
                                        .with(csrf())
                                        .cookie(session)
                                        .header("X-User-Id", otherUser)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(uniqueBody()))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String backtestId = JsonPath.read(response, "$.data.backtestId");
        Long storedRequester =
                jdbcTemplate.queryForObject(
                        "SELECT requester_id FROM backtest_request WHERE backtest_id = ?",
                        Long.class,
                        backtestId);
        assertThat(storedRequester).isEqualTo(sessionUser).isNotEqualTo(otherUser);
    }
}
