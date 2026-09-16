package com.ssafy.thorp.backtest.application.service;

import static com.ssafy.thorp.support.Requesters.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.backtest.application.port.BacktestJobPublisher;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 발행 <b>성공</b> 경로가 DB에 남기는 것(spec 004 research R-18).
 *
 * <p>브로커는 목이다 — 여기서 확인할 것은 메시지가 나갔는지가 아니라 <b>트랜잭션 경계</b>다. 발행은 {@code AFTER_COMMIT} 콜백에서 일어나는데, 그
 * 시점의 원래 트랜잭션은 이미 커밋됐지만 자원은 아직 묶여 있다. 기본 전파로 DB를 건드리면 끝난 트랜잭션에 참여해 변경이 <b>다시 커밋되지 않는다</b> — 발행에
 * 성공해도 {@code published_at}이 NULL로 남고 재발행기가 멀쩡한 작업을 계속 다시 보낸다. 그래서 새 트랜잭션으로 쓴다.
 *
 * <p>두 번째 시험은 <b>덮어쓰기</b>다. 발행 경로와 이벤트 처리는 서로 다른 스레드에서 동시에 돈다. 발행 경로가 실행 전체를 읽어 통째로 저장하면, 그 사이 이벤트가
 * 바꿔 놓은 상태를 되돌린다. 발행 경로는 자기 컬럼만 써야 한다.
 */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "thorp.backtest.republish.enabled=false",
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
@AutoConfigureMockMvc
class BacktestPublishRecordIT extends PostgresTestSupport {

    private static final Path REQUEST_FIXTURE =
            Path.of("..", "docs", "specs", "backtest-api", "examples", "backtest-request.json");

    @Autowired private MockMvc mockMvc;
    @Autowired private BacktestJobDispatcher dispatcher;
    @Autowired private BacktestRunRepository runRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    /** 브로커 없이 발행을 성공시킨다. 이 테스트의 관심사는 발행 자체가 아니라 그 뒤의 DB 기록이다. */
    @MockitoBean private BacktestJobPublisher publisher;

    /**
     * 계약 예시를 그대로 쓰되 시작 금액만 매번 다르게 한다.
     *
     * <p>같은 본문은 같은 동일 버전 키를 만들고, PostgreSQL 컨테이너는 테스트 클래스끼리 공유된다. 흔들지 않으면 뒤 테스트가 앞 테스트의 실행에
     * 합류(JOINED)해 상태가 섞인다 — 그건 제품 동작이 맞지만 이 테스트들이 보려는 것이 아니다.
     */
    private String uniqueBody() throws java.io.IOException {
        long unique = 10_000_000L + (System.nanoTime() % 1_000_000L);
        return Files.readString(REQUEST_FIXTURE).replace("10000000.00", unique + ".00");
    }

    private long newUser() {
        String suffix = Long.toString(System.nanoTime() % 1_000_000_000L);
        String email = "pubrec-" + suffix + "@example.com";
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

    private long acceptRun() throws Exception {
        long userId = transactionTemplate.execute(status -> newUser());
        String body = uniqueBody();
        String backtestId =
                com.jayway.jsonpath.JsonPath.read(
                        mockMvc.perform(
                                        post("/backtests")
                                                .with(csrf())
                                                .with(as(userId))
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(body))
                                .andExpect(status().isAccepted())
                                .andReturn()
                                .getResponse()
                                .getContentAsString(),
                        "$.data.backtestId");
        return transactionTemplate.execute(
                status ->
                        jdbcTemplate.queryForObject(
                                "SELECT run_id FROM backtest_request WHERE backtest_id = ?",
                                Long.class,
                                backtestId));
    }

    private <T> T column(String name, long runId, Class<T> type) {
        return transactionTemplate.execute(
                status ->
                        jdbcTemplate.queryForObject(
                                "SELECT " + name + " FROM backtest_run WHERE id = ?", type, runId));
    }

    @Test
    @DisplayName("발행에 성공하면 published_at 이 실제로 커밋된다 — 재발행기가 다시 집어 가지 않는다")
    void publishedAtIsCommitted() throws Exception {
        long runId = acceptRun();

        assertThat(column("published_at", runId, java.sql.Timestamp.class))
                .as("AFTER_COMMIT 에서 기본 전파로 쓰면 여기가 NULL 로 남는다")
                .isNotNull();
        assertThat(column("publish_count", runId, Integer.class)).isEqualTo(1);
        assertThat(column("status", runId, String.class)).isEqualTo("QUEUED");
    }

    /**
     * 이벤트 트랜잭션이 <b>오래된 영속성 컨텍스트</b>를 들고 저장할 때 발행 횟수가 살아남는지.
     *
     * <p>Java 쪽에서 건드리는 필드를 갈라 놓는 것만으로는 모자란다. Hibernate 의 기본 UPDATE 는 바꾸지 않은 컬럼까지 함께 쓰기 때문에, 컨텍스트에
     * 올라온 시점의 옛 값이 그대로 나간다. 앞선 저장소 테스트는 중간에 컨텍스트를 비워 최신 엔티티를 다시 읽으므로 이 경우를 놓친다 — 실제 사고는 이벤트 트랜잭션이
     * 실행을 읽어 둔 채로 진행되는 동안 발행 경로가 끼어들 때 일어난다.
     */
    @Test
    @DisplayName("오래된 영속성 컨텍스트로 상태를 저장해도 발행 횟수가 되돌아가지 않는다")
    void staleContextDoesNotResetPublishCount() throws Exception {
        long runId = acceptRun();
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        transactionTemplate.executeWithoutResult(
                status -> {
                    // 1) 이벤트 트랜잭션이 실행을 읽는다(컨텍스트에 publish_count=1 로 올라온다).
                    BacktestRun stale = runRepository.findById(runId).orElseThrow();
                    // 2) 그 사이 발행 경로가 다른 트랜잭션에서 시도 횟수를 올리고 커밋한다.
                    requiresNew.executeWithoutResult(
                            inner -> runRepository.recordPublishAttempt(runId));
                    // 3) 이벤트 트랜잭션이 이제 상태를 저장한다.
                    runRepository.update(stale.markRunning("exec-1"));
                });

        assertThat(column("publish_count", runId, Integer.class))
                .as("바꾸지 않은 컬럼까지 쓰면 여기가 1로 내려간다")
                .isEqualTo(2);
        assertThat(column("status", runId, String.class)).isEqualTo(RunStatus.RUNNING.name());
    }

    @Test
    @DisplayName("재발행이 실행 상태를 되돌리지 않는다 — 발행 경로는 자기 컬럼만 쓴다")
    void republishDoesNotOverwriteRunState() throws Exception {
        long runId = acceptRun();

        // 이벤트가 실행을 RUNNING 으로 옮긴 뒤에 발행 경로가 다시 저장하는 상황이다.
        transactionTemplate.executeWithoutResult(
                status -> {
                    BacktestRun run = runRepository.findById(runId).orElseThrow();
                    runRepository.update(run.markRunning("exec-1"));
                });

        dispatcher.republish(runId);

        assertThat(column("status", runId, String.class))
                .as("발행 기록이 상태를 QUEUED 로 되돌리면 안 된다")
                .isEqualTo(RunStatus.RUNNING.name());
        assertThat(column("current_execution_id", runId, String.class)).isEqualTo("exec-1");
        assertThat(column("publish_count", runId, Integer.class))
                .as("재발행은 시도 횟수를 한 번 더 올린다")
                .isEqualTo(2);
        assertThat(column("published_at", runId, java.sql.Timestamp.class)).isNotNull();
    }
}
