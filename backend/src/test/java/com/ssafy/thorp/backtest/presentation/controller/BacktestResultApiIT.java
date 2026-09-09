package com.ssafy.thorp.backtest.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.backtest.application.command.SaveBacktestResultCommand;
import com.ssafy.thorp.backtest.application.port.message.BacktestEvent;
import com.ssafy.thorp.backtest.application.port.message.FinishedPayload;
import com.ssafy.thorp.backtest.application.repository.BacktestJobRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestResultRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.backtest.infrastructure.messaging.MessageFixtures;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 계약 픽스처를 저장하고 네 엔드포인트를 실제로 호출한다.
 *
 * <p><b>응답이 {@code docs/specs/backtest-api/examples/}의 예시 파일과 같은지 단언한다.</b> 계약 문서가 구현과 어긋나면 여기서 깨진다
 * — 문서를 먼저 커밋했으므로 이 테스트가 그것을 지키는 장치다.
 *
 * <p>브로커 컨테이너는 띄우지 않는다({@code BacktestEventFlowIT}와 같은 방식). DB는 PostgreSQL 컨테이너다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.rabbitmq.host=localhost",
            "spring.rabbitmq.port=1",
            "spring.rabbitmq.connection-timeout=1s",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
@AutoConfigureMockMvc
class BacktestResultApiIT extends PostgresTestSupport {

    /** gradle test의 작업 디렉터리는 backend/다. */
    private static final Path EXAMPLES = Path.of("..", "docs", "specs", "backtest-api", "examples");

    @Autowired private MockMvc mockMvc;
    @Autowired private BacktestJobRepository jobRepository;
    @Autowired private BacktestResultRepository resultRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TestRestTemplate restTemplate;

    /** 계약 픽스처와 같은 조건의 Job. config 단언이 여기 값과 맞아야 한다. */
    private BacktestJob newJob() {
        return jobRepository.save(
                BacktestJob.create(
                        "MA_CROSSOVER",
                        "1.0.0",
                        Map.of("shortWindow", 20, "longWindow", 60, "threshold", 0.05),
                        List.of(new InstrumentId("005930.XKRX"), new InstrumentId("000660.XKRX")),
                        new BacktestPeriod(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 6, 30)),
                        new BigDecimal("10000000"),
                        PositionSizingMode.STRATEGY_DEFINED,
                        new BigDecimal("0.50"),
                        new InstrumentId("069500.XKRX"),
                        42));
    }

    /**
     * 결과 저장은 <b>한 트랜잭션 안에서</b> 해야 한다 — 결과 저장 포트의 계약이고, Job을 {@code getReferenceById} 프록시로 참조하기
     * 때문이다. 운영에서는 {@code @Transactional} 서비스가 그 경계를 만든다.
     */
    private BacktestJob saveResult(BacktestEvent<FinishedPayload> event, boolean completed) {
        return transactionTemplate.execute(
                status -> {
                    BacktestJob job = newJob();
                    resultRepository.save(
                            new SaveBacktestResultCommand(
                                    job.getId(),
                                    event.executionId(),
                                    event.eventId(),
                                    event.payload().engineOutput(),
                                    event.payload().benchmark()));
                    return jobRepository.update(completed ? job.markCompleted() : job.markFailed());
                });
    }

    private BacktestJob completedJob() {
        return saveResult(MessageFixtures.finishedCompleted(), true);
    }

    private BacktestJob failedJob() {
        return saveResult(MessageFixtures.finishedFailed(), false);
    }

    /** 예시 파일의 {@code data}. 예시의 backtestId는 픽스처 값이라 실제 응답과 다르므로 그 필드만 맞춰 준다. */
    private JsonNode expectedData(String file, String actualBacktestId) throws Exception {
        JsonNode data = objectMapper.readTree(Files.readString(EXAMPLES.resolve(file))).get("data");
        ((com.fasterxml.jackson.databind.node.ObjectNode) data).put("backtestId", actualBacktestId);
        return data;
    }

    private JsonNode actualData(String url) throws Exception {
        String body =
                mockMvc.perform(get(url))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return objectMapper.readTree(body).get("data");
    }

    @Test
    @DisplayName("상세 응답이 계약 예시와 같다")
    void detailMatchesExample() throws Exception {
        String id = completedJob().getBacktestId().value();

        assertThat(actualData("/backtests/" + id))
                .isEqualTo(expectedData("backtest-detail.json", id));
    }

    @Test
    @DisplayName("거래 응답이 계약 예시와 같다 — entryDate 변환과 OPEN의 명시적 null 포함")
    void tradesMatchExample() throws Exception {
        String id = completedJob().getBacktestId().value();

        assertThat(actualData("/backtests/" + id + "/trades"))
                .isEqualTo(expectedData("backtest-trades.json", id));
    }

    @Test
    @DisplayName("보유 응답이 계약 예시와 같다 — 직전 스냅샷 규칙")
    void positionsMatchExample() throws Exception {
        String id = completedJob().getBacktestId().value();
        JsonNode expected = expectedData("backtest-positions.json", id);

        assertThat(
                        actualData(
                                "/backtests/"
                                        + id
                                        + "/positions?date="
                                        + expected.get("requestedDate").asText()))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("시계열은 픽스처 전체 길이이고 앞 3점이 계약 예시(발췌)와 같다")
    void timeseriesMatchesExampleExcerpt() throws Exception {
        String id = completedJob().getBacktestId().value();
        JsonNode expected = expectedData("backtest-timeseries.json", id);

        JsonNode actual = actualData("/backtests/" + id + "/timeseries");

        assertThat(actual.get("points")).hasSize(129);
        for (int i = 0; i < expected.get("points").size(); i++) {
            assertThat(actual.get("points").get(i)).isEqualTo(expected.get("points").get(i));
        }
    }

    @Test
    @DisplayName("결과 전에는 하위 리소스가 409 RESULT_NOT_READY다 — 빈 배열이 아니다")
    void subResourcesReturnConflictBeforeResult() throws Exception {
        String id = newJob().getBacktestId().value();

        for (String path : List.of("/timeseries", "/trades", "/positions?date=2025-01-02")) {
            mockMvc.perform(get("/backtests/" + id + path))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("RESULT_NOT_READY"));
        }
        // 상세는 200이고 config만 있다
        mockMvc.perform(get("/backtests/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.config.strategyId").value("MA_CROSSOVER"))
                .andExpect(jsonPath("$.data.summary").doesNotExist());
    }

    @Test
    @DisplayName("FAILED는 상세가 200 + error이고 하위 리소스는 409 BACKTEST_FAILED다")
    void failedJobExposesErrorButNotSubResources() throws Exception {
        String id = failedJob().getBacktestId().value();

        mockMvc.perform(get("/backtests/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.error.code").value("NO_MARKET_DATA"))
                .andExpect(jsonPath("$.data.summary").doesNotExist());

        for (String path : List.of("/timeseries", "/trades", "/positions?date=2025-01-02")) {
            mockMvc.perform(get("/backtests/" + id + path))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("BACKTEST_FAILED"));
        }
    }

    @Test
    @DisplayName("COMPLETED인데 결과 행이 없으면 네 엔드포인트 모두 500이다 — 거래 목록도 내보내지 않는다")
    void missingResultRowIsServerErrorEverywhere() throws Exception {
        BacktestJob job = completedJob();
        String id = job.getBacktestId().value();
        // 결과 행만 지운다. 거래 행은 남겨 둔다 — /trades가 그것만 보고 200을 주면 안 된다
        jdbcTemplate.update("DELETE FROM backtest_result WHERE backtest_job_id = ?", job.getId());
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM backtest_trade WHERE backtest_job_id = ?",
                                Long.class,
                                job.getId()))
                .isEqualTo(3);

        for (String path : List.of("", "/timeseries", "/trades", "/positions?date=2025-01-22")) {
            mockMvc.perform(get("/backtests/" + id + path))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
        }
    }

    @Test
    @DisplayName("시계열 응답은 gzip으로 압축된다 — 이 앱에서 가장 큰 응답이다")
    void timeseriesIsGzipped() {
        String id = completedJob().getBacktestId().value();
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_ENCODING, "gzip");

        // MockMvc는 Tomcat을 거치지 않아 압축을 확인할 수 없다. 실제 포트로 호출한다.
        ResponseEntity<byte[]> response =
                restTemplate.exchange(
                        "/backtests/" + id + "/timeseries",
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        byte[].class);

        assertThat(response.getHeaders().get(HttpHeaders.CONTENT_ENCODING)).containsExactly("gzip");
        // 압축 전 본문이 min-response-size(2KB)를 넘어야 압축된다 — 129점 시계열이면 충분하다
        assertThat(response.getBody()).isNotEmpty();
    }

    @Test
    @DisplayName("없는 backtestId는 네 엔드포인트 모두 404다")
    void unknownBacktestIdIsNotFound() throws Exception {
        String missing = "BT-29991231-999999";

        for (String path : List.of("", "/timeseries", "/trades", "/positions?date=2025-01-02")) {
            mockMvc.perform(get("/backtests/" + missing + path))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        }
    }
}
