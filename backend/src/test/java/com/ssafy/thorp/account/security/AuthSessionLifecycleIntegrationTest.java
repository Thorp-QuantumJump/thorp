package com.ssafy.thorp.account.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ssafy.thorp.account.application.repository.UserRepository;
import com.ssafy.thorp.account.domain.model.User;
import com.ssafy.thorp.account.domain.model.UserStatus;
import com.ssafy.thorp.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * 세션이 언제 사라지고 무엇이 남는지를 실제 저장소 위에서 고정한다.
 *
 * <p>로그인·로그아웃은 각자의 테스트가 지키고, 여기서는 그 사이의 생명주기를 본다 — 아무 요청이 없을 때의 만료, 인증 경계에서의 CSRF 토큰 교체, 로그인 시 식별자
 * 폐기, 로그에 남는 것, 그리고 이 모든 것이 올라탄 마이그레이션 스키마다.
 *
 * <p>만료는 30분을 기다려 확인할 수 없으므로 세션 행의 마지막 접근 시각을 과거로 밀어 재현한다. Spring Session은 세션을 읽을 때마다 그 값으로 만료를
 * 판정하므로, 시간을 앞당기는 것과 같은 경로를 지난다.
 */
class AuthSessionLifecycleIntegrationTest extends IntegrationTestSupport {

    /** Spring Session JDBC가 직접 들고 다니는 공식 PostgreSQL 스키마. 마이그레이션은 이것과 같은 모양이어야 한다. */
    private static final String OFFICIAL_SCHEMA_SCRIPT =
            "org/springframework/session/jdbc/schema-postgresql.sql";

    private static final Duration EXPECTED_TIMEOUT = Duration.ofMinutes(30);

    private static final String EMAIL = "lifecycle-it-member@example.com";
    private static final String PASSWORD = "password123";
    private static final String NICKNAME = "생명주기회원";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from spring_session");
        jdbcTemplate.update("delete from users where email like 'lifecycle-it-%'");
        userId = saveUser().getId();
    }

    @Test
    @DisplayName("세션은 마지막 요청으로부터 30분 동안만 유효하다")
    void sessionCarriesTheThirtyMinuteInactivityWindow() throws Exception {
        loginAndGetSessionCookie();

        Integer maxInactiveInterval =
                jdbcTemplate.queryForObject(
                        "select max_inactive_interval from spring_session", Integer.class);

        assertThat(maxInactiveInterval).isEqualTo((int) EXPECTED_TIMEOUT.toSeconds());
    }

    @Test
    @DisplayName("30분 넘게 요청이 없던 세션은 만료되어 401이 되고 저장소에서도 사라진다")
    void idleSessionExpiresAndIsRemoved() throws Exception {
        Cookie session = loginAndGetSessionCookie();
        mockMvc.perform(get("/auth/me").cookie(session)).andExpect(status().isOk());

        idleFor(EXPECTED_TIMEOUT.plusMinutes(1));

        mockMvc.perform(get("/auth/me").cookie(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));

        assertThat(sessionCount()).isZero();
    }

    @Test
    @DisplayName("만료 직전까지 쉬었던 세션은 아직 살아 있고, 그 요청이 만료 시계를 다시 감는다")
    void requestBeforeTheDeadlineKeepsTheSessionAlive() throws Exception {
        Cookie session = loginAndGetSessionCookie();

        idleFor(EXPECTED_TIMEOUT.minusMinutes(1));
        mockMvc.perform(get("/auth/me").cookie(session)).andExpect(status().isOk());

        // 방금 요청으로 마지막 접근 시각이 갱신됐으므로 29분을 더 쉬어도 아직 만료가 아니다.
        idleFor(EXPECTED_TIMEOUT.minusMinutes(1));
        mockMvc.perform(get("/auth/me").cookie(session)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("로그인은 브라우저가 들고 있던 CSRF 토큰을 새 값으로 바꾼다")
    void loginReplacesTheCsrfTokenTheBrowserHeld() throws Exception {
        Cookie before = freshCsrfToken();

        MvcResult result = mockMvc.perform(login(before)).andExpect(status().isOk()).andReturn();

        Cookie after = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(after).isNotNull();
        assertThat(after.getValue()).isNotBlank().isNotEqualTo(before.getValue());
        assertThat(after.isHttpOnly()).isFalse();
        assertThat(after.getPath()).isEqualTo("/");
    }

    @Test
    @DisplayName("로그아웃도 새 CSRF 토큰을 함께 내려 다음 요청이 바로 가능하다")
    void logoutIssuesAFreshCsrfToken() throws Exception {
        Cookie session = loginAndGetSessionCookie();
        Cookie before = freshCsrfToken();

        MvcResult result =
                mockMvc.perform(
                                delete("/auth/sessions/current")
                                        .cookie(session, before)
                                        .header("X-XSRF-TOKEN", before.getValue()))
                        .andExpect(status().isNoContent())
                        .andReturn();

        Cookie after = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(after).isNotNull();
        assertThat(after.getValue()).isNotBlank().isNotEqualTo(before.getValue());

        // 새 토큰만으로 다음 상태 변경 요청이 통과한다 — FE가 /auth/csrf를 다시 부르지 않아도 된다.
        mockMvc.perform(
                        delete("/auth/sessions/current")
                                .cookie(after)
                                .header("X-XSRF-TOKEN", after.getValue()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("로그인은 그때까지 쓰던 세션 식별자를 폐기하고 행도 남기지 않는다")
    void loginDiscardsThePreviousSessionIdentifier() throws Exception {
        Cookie first = loginAndGetSessionCookie();

        Cookie rotated =
                mockMvc.perform(login(freshCsrfToken()).cookie(first))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getCookie("THORP_SESSION");

        assertThat(rotated).isNotNull();
        assertThat(rotated.getValue()).isNotEqualTo(first.getValue());
        assertThat(sessionCount()).isEqualTo(1);
        assertThat(sessionRowsFor(first.getValue())).isZero();
    }

    @Test
    @DisplayName("인증 로그에는 이메일·비밀번호·세션 식별자가 남지 않는다")
    void authenticationLogsCarryNoCredentialsOrSessionIdentifiers() throws Exception {
        List<String> messages = new ArrayList<>();
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);

        Cookie session;
        try {
            // 로그가 실제로 쓰이는 경로를 모두 지난다. 거절 경로가 특히 중요하다 — 사유를 남기면서
            // 무엇을 함께 남기는지가 여기서 갈린다.
            mockMvc.perform(login(freshCsrfToken(), EMAIL, "wrong-password"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(login(freshCsrfToken(), "lifecycle-it-nobody@example.com", PASSWORD))
                    .andExpect(status().isUnauthorized());
            session = loginAndGetSessionCookie();
            mockMvc.perform(logout(session)).andExpect(status().isNoContent());
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }

        appender.list.forEach(event -> messages.add(event.getFormattedMessage()));

        assertThat(messages).isNotEmpty();
        assertThat(messages).noneMatch(message -> message.contains(session.getValue()));
        assertThat(messages).noneMatch(message -> message.contains(EMAIL));
        assertThat(messages).noneMatch(message -> message.contains(PASSWORD));
        assertThat(messages).noneMatch(message -> message.contains("wrong-password"));
    }

    @Test
    @DisplayName("마이그레이션이 만든 세션 스키마는 Spring Session이 요구하는 표와 열을 모두 갖는다")
    void migrationMatchesTheSchemaSpringSessionExpects() throws Exception {
        // 운영은 Flyway만 스키마를 만든다(initialize-schema: never). Spring Session을 올릴 때 열이
        // 하나 늘면 운영에서만 조용히 깨지므로, 라이브러리가 들고 있는 공식 스크립트와 대조한다.
        var expected = expectedSessionSchema();
        assertThat(expected).containsKeys("spring_session", "spring_session_attributes");

        expected.forEach(
                (table, columns) ->
                        assertThat(liveColumnsOf(table))
                                .as("마이그레이션의 %s 열", table)
                                .containsAll(columns));
    }

    @Test
    @DisplayName("세션 저장소는 마이그레이션이 만든 표에 인증 상태를 그대로 쓴다")
    void sessionRepositoryWritesIntoTheMigratedTables() throws Exception {
        // 위 대조가 통과해도 앱이 다른 표를 쓰고 있으면 의미가 없다. 로그인 한 번으로 실제 기록을 확인한다.
        loginAndGetSessionCookie();

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
    }

    /** 세션이 마지막으로 쓰인 시각을 과거로 민다. Spring Session은 이 값으로 만료를 판정한다. */
    private void idleFor(Duration idle) {
        long lastAccess = Instant.now().minus(idle).toEpochMilli();
        jdbcTemplate.update(
                "update spring_session set last_access_time = ?,"
                        + " expiry_time = ? + max_inactive_interval * 1000",
                lastAccess,
                lastAccess);
    }

    /** {@value #OFFICIAL_SCHEMA_SCRIPT}의 {@code CREATE TABLE}에서 표 이름과 열 이름을 읽는다. */
    private static java.util.Map<String, Set<String>> expectedSessionSchema() throws IOException {
        java.util.Map<String, Set<String>> schema = new java.util.LinkedHashMap<>();
        String script = readOfficialSchemaScript();

        Matcher tables =
                Pattern.compile(
                                "CREATE TABLE\\s+(\\w+)\\s*\\((.*?)\\n\\s*\\)",
                                Pattern.DOTALL | Pattern.CASE_INSENSITIVE)
                        .matcher(script);

        while (tables.find()) {
            Set<String> columns = new LinkedHashSet<>();
            for (String line : tables.group(2).split(",\\s*\\n")) {
                String first = line.strip().split("\\s+")[0].toLowerCase(Locale.ROOT);
                if (!first.isEmpty() && !"constraint".equals(first)) {
                    columns.add(first);
                }
            }
            schema.put(tables.group(1).toLowerCase(Locale.ROOT), columns);
        }
        return schema;
    }

    private static String readOfficialSchemaScript() throws IOException {
        try (InputStream script =
                JdbcIndexedSessionRepository.class
                        .getClassLoader()
                        .getResourceAsStream(OFFICIAL_SCHEMA_SCRIPT)) {
            assertThat(script).as("Spring Session의 %s", OFFICIAL_SCHEMA_SCRIPT).isNotNull();
            return new String(script.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private List<String> liveColumnsOf(String table) {
        return jdbcTemplate.queryForList(
                "select column_name from information_schema.columns"
                        + " where table_schema = current_schema() and table_name = ?",
                String.class,
                table);
    }

    private RequestBuilder logout(Cookie session) throws Exception {
        Cookie csrf = freshCsrfToken();
        return delete("/auth/sessions/current")
                .cookie(session, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(
            Cookie csrf) {
        return login(csrf, EMAIL, PASSWORD);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(
            Cookie csrf, String email, String password) {
        return post("/auth/sessions")
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password));
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
        Cookie session =
                mockMvc.perform(login(freshCsrfToken()))
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

    private Integer sessionRowsFor(String sessionId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from spring_session where session_id = ?",
                Integer.class,
                sessionId);
    }

    private User saveUser() {
        return userRepository.save(
                new User(
                        null,
                        EMAIL,
                        passwordEncoder.encode(PASSWORD),
                        NICKNAME,
                        "01011112222",
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
