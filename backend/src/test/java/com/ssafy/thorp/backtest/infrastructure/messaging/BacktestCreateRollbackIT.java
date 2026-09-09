package com.ssafy.thorp.backtest.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * 브로커에 닿지 못할 때 Job 저장이 롤백되는지 전체 컨텍스트로 확인한다.
 *
 * <p>큐에 들어가지 못한 Job이 QUEUED로 남으면 사용자는 영원히 끝나지 않는 백테스트를 보게 된다. 그것을 막는 것이 생성 트랜잭션의 존재 이유이므로, 서비스 단위
 * 테스트(모킹)와 별개로 실제 트랜잭션 경계에서 한 번 확인한다.
 *
 * <p>브로커 컨테이너는 띄우지 않는다. 아무도 듣지 않는 포트를 브로커로 지정해 연결 실패를 만든다. DB는 {@link PostgresTestSupport}가 주는
 * PostgreSQL 컨테이너를 쓰므로 Docker가 없으면 건너뛴다.
 */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.host=localhost",
            "spring.rabbitmq.port=1",
            "spring.rabbitmq.connection-timeout=1s",
            // 결과 이벤트 리스너가 죽은 브로커에 계속 재연결을 시도하지 않도록 끈다. 이 테스트는 발행 실패만 본다.
            "spring.rabbitmq.listener.simple.auto-startup=false",
            // 메일 자격 증명은 운영에서만 주입된다. 전체 컨텍스트를 띄우는 테스트라
            // 값이 없으면 메일 발송 빈 생성 단계에서 플레이스홀더 해석이 실패한다.
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
@AutoConfigureMockMvc
class BacktestCreateRollbackIT extends PostgresTestSupport {

    private static final Path REQUEST_FIXTURE =
            Path.of("..", "docs", "specs", "backtest-api", "examples", "backtest-request.json");

    @Autowired private MockMvc mockMvc;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    private long storedJobs() {
        return transactionTemplate.execute(
                status ->
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM backtest_job", Long.class));
    }

    @Test
    @DisplayName("브로커에 닿지 못하면 503을 주고 Job을 저장하지 않는다")
    void rollsBackWhenBrokerUnreachable() throws IOException, Exception {
        String body = Files.readString(REQUEST_FIXTURE);
        long before = storedJobs();

        mockMvc.perform(post("/backtests").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("BROKER_UNAVAILABLE"));

        // 요청 트랜잭션이 끝난 뒤 별도 트랜잭션에서 확인한다. 롤백됐다면 행 수가 그대로다.
        // 절대 개수(또는 id 1의 존재)로 단언하지 않는다 — PostgreSQL 컨테이너가 싱글턴이라 다른 테스트 클래스가
        // 커밋한 Job이 같은 DB에 남아 있고, 시퀀스는 롤백돼도 앞으로 나간다.
        assertThat(storedJobs()).isEqualTo(before);
    }
}
