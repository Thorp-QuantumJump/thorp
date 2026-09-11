package com.ssafy.thorp.backtest.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.backtest.presentation.support.CurrentRequester;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 커밋 후 발행과 재발행(spec 004 research R-18).
 *
 * <p>예전에는 브로커에 닿지 못하면 접수를 롤백해 503을 돌려줬다. 그 방식은 반대 사고(발행 성공 후 커밋 실패)를 막지 못했고, 무엇보다 워커의 STARTED가 커밋을
 * 앞질러 도착하면 결과 Consumer가 모르는 실행이라며 버렸다. 이제는 <b>커밋을 먼저 하고 발행은 그 뒤에</b> 한다.
 *
 * <p>그래서 이 테스트가 확인하는 것이 달라졌다. 브로커가 죽어 있어도 접수는 성공하고(202), 실행은 {@code QUEUED}·{@code published_at
 * NULL}로 남아 재발행 대상이 된다. 사용자는 "접수됐다"는 답을 받고, 브로커가 살아나면 작업이 나간다.
 *
 * <p>브로커 컨테이너는 띄우지 않는다 — 아무도 듣지 않는 포트를 지정해 연결 실패를 만든다. DB는 {@link PostgresTestSupport}의 PostgreSQL
 * 컨테이너라 Docker가 없으면 건너뛴다.
 */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.host=localhost",
            "spring.rabbitmq.port=1",
            "spring.rabbitmq.connection-timeout=1s",
            // 결과 이벤트 리스너가 죽은 브로커에 계속 재연결을 시도하지 않도록 끈다.
            "spring.rabbitmq.listener.simple.auto-startup=false",
            // 스케줄러가 제멋대로 끼어들지 않게 끄고, 재발행은 테스트가 직접 부른다.
            "thorp.backtest.republish.enabled=false",
            "thorp.auth.trust-user-id-header=true",
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
@AutoConfigureMockMvc
class BacktestPublishAfterCommitIT extends PostgresTestSupport {

    private static final Path REQUEST_FIXTURE =
            Path.of("..", "docs", "specs", "backtest-api", "examples", "backtest-request.json");

    @Autowired private MockMvc mockMvc;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** users FK를 만족시킬 최소 행. 회원 도메인을 끌어오지 않으려고 직접 넣는다. */
    private long newUser() {
        // phone_number 는 varchar(20) 이다. 긴 이메일을 그대로 넣으면 길이 제약에 걸린다.
        String suffix = Long.toString(System.nanoTime() % 1_000_000_000L);
        String email = "publish-" + suffix + "@example.com";
        jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, nickname, phone_number, status,"
                        + " paper_ranking_enabled, live_ranking_enabled, ranking_display_nickname,"
                        + " created_at, updated_at)"
                        + " VALUES (?, 'x', ?, ?, 'ACTIVE', false, false, false, NOW(), NOW())",
                email,
                "u" + suffix,
                suffix);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return transactionTemplate.execute(status -> work.get());
    }

    @Test
    @DisplayName("브로커가 죽어 있어도 접수는 202이고 실행은 발행 대기로 남는다")
    void acceptsEvenWhenBrokerIsDown() throws IOException, Exception {
        String body = Files.readString(REQUEST_FIXTURE);
        long userId = newUser();

        String backtestId =
                com.jayway.jsonpath.JsonPath.read(
                        mockMvc.perform(
                                        post("/backtests")
                                                .header(CurrentRequester.DEV_HEADER, userId)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(body))
                                .andExpect(status().isAccepted())
                                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                                .andExpect(jsonPath("$.data.resultSource").value("NEW"))
                                .andReturn()
                                .getResponse()
                                .getContentAsString(),
                        "$.data.backtestId");

        Long runId =
                inTransaction(
                        () ->
                                jdbcTemplate.queryForObject(
                                        "SELECT run_id FROM backtest_request WHERE backtest_id = ?",
                                        Long.class,
                                        backtestId));

        assertThat(runId).isNotNull();
        assertThat(column("status", runId)).isEqualTo("QUEUED");
        assertThat(column("published_at", runId)).as("발행 확인이 없다 — 재발행 대상이다").isNull();
        assertThat(intColumn("publish_count", runId))
                .as("시도는 커밋 전에 예고한다. 실제 메시지 수의 상한이다")
                .isEqualTo(1);
        assertThat(column("queued_at", runId)).isNotNull();
        assertThat(column("dedup_key", runId)).isNotNull();
    }

    private String column(String name, long runId) {
        Object value =
                inTransaction(
                        () ->
                                jdbcTemplate.queryForObject(
                                        "SELECT " + name + " FROM backtest_run WHERE id = ?",
                                        Object.class,
                                        runId));
        return value == null ? null : value.toString();
    }

    private int intColumn(String name, long runId) {
        return inTransaction(
                () ->
                        jdbcTemplate.queryForObject(
                                "SELECT " + name + " FROM backtest_run WHERE id = ?",
                                Integer.class,
                                runId));
    }
}
