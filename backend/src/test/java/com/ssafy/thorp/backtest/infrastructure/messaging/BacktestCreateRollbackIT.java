package com.ssafy.thorp.backtest.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.backtest.application.repository.BacktestJobRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 브로커에 닿지 못할 때 Job 저장이 롤백되는지 전체 컨텍스트로 확인한다.
 *
 * <p>큐에 들어가지 못한 Job이 QUEUED로 남으면 사용자는 영원히 끝나지 않는 백테스트를 보게 된다. 그것을 막는 것이 생성 트랜잭션의 존재 이유이므로, 서비스 단위
 * 테스트(모킹)와 별개로 실제 트랜잭션 경계에서 한 번 확인한다.
 *
 * <p>Docker가 필요 없다. 아무도 듣지 않는 포트를 브로커로 지정해 연결 실패를 만들고, DB는 H2를 쓴다.
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:rollback-it;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.rabbitmq.host=localhost",
            "spring.rabbitmq.port=1",
            "spring.rabbitmq.connection-timeout=1s"
        })
@AutoConfigureMockMvc
class BacktestCreateRollbackIT {

    private static final Path REQUEST_FIXTURE =
            Path.of("..", "docs", "specs", "backtest-api", "examples", "backtest-request.json");

    @Autowired private MockMvc mockMvc;
    @Autowired private BacktestJobRepository repository;
    @Autowired private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("브로커에 닿지 못하면 503을 주고 Job을 저장하지 않는다")
    void rollsBackWhenBrokerUnreachable() throws IOException, Exception {
        String body = Files.readString(REQUEST_FIXTURE);

        mockMvc.perform(post("/backtests").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("BROKER_UNAVAILABLE"));

        // 요청 트랜잭션이 끝난 뒤 별도 트랜잭션에서 확인한다. 롤백됐다면 어떤 backtestId로도 찾을 수 없다.
        Boolean anyJobStored =
                transactionTemplate.execute(status -> repository.findById(1L).isPresent());
        assertThat(anyJobStored).isFalse();
    }
}
