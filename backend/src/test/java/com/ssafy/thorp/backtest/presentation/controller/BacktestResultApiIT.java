package com.ssafy.thorp.backtest.presentation.controller;

import static com.ssafy.thorp.support.Requesters.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.backtest.application.command.SaveBacktestResultCommand;
import com.ssafy.thorp.backtest.application.port.message.BacktestEvent;
import com.ssafy.thorp.backtest.application.port.message.FinishedPayload;
import com.ssafy.thorp.backtest.application.repository.BacktestRequestRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestResultRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.model.PublicResultToken;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.model.RunPlan;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.backtest.infrastructure.messaging.MessageFixtures;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
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
            "thorp.backtest.republish.enabled=false",
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
@AutoConfigureMockMvc
class BacktestResultApiIT extends PostgresTestSupport {

    /** gradle test의 작업 디렉터리는 backend/다. */
    private static final Path EXAMPLES = Path.of("..", "docs", "specs", "backtest-api", "examples");

    @Autowired private MockMvc mockMvc;
    @Autowired private BacktestRunRepository jobRepository;
    @Autowired private BacktestRequestRepository requestRepository;
    @Autowired private BacktestResultRepository resultRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TestRestTemplate restTemplate;

    /** users FK를 만족시킬 최소 행. 회원 도메인을 끌어오지 않으려고 직접 넣는다. */
    private long newUser() {
        // phone_number 는 varchar(20) 이다. 긴 이메일을 그대로 넣으면 길이 제약에 걸린다.
        String suffix = Long.toString(System.nanoTime() % 1_000_000_000L);
        String email = "result-" + suffix + "@example.com";
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

    /** 계약 픽스처와 같은 조건의 실행. config 단언이 여기 값과 맞아야 한다. */
    private BacktestRun newJob() {
        return jobRepository.save(
                BacktestRun.create(
                                "MA_CROSSOVER",
                                "1.0.0",
                                Map.of("shortWindow", 20, "longWindow", 60, "threshold", 0.05),
                                List.of(
                                        new InstrumentId("005930.XKRX"),
                                        new InstrumentId("000660.XKRX")),
                                new BacktestPeriod(
                                        LocalDate.of(2025, 1, 1), LocalDate.of(2025, 6, 30)),
                                new BigDecimal("10000000"),
                                PositionSizingMode.STRATEGY_DEFINED,
                                new BigDecimal("0.50"),
                                new InstrumentId("069500.XKRX"),
                                42)
                        .withPlan(plannedVersions()));
    }

    /**
     * 접수가 남기는 참조 버전(T018)만 있는 계획. 예시 {@code backtest-detail.json}의 {@code versions}와 같아야 하고, 상세는
     * 여기에 결과 행의 {@code worker}(메시징 픽스처 {@code event-finished-completed.json}의 값)를 얹는다. 동일 버전 키는 두지
     * 않는다 — 부분 유니크 인덱스가 COMPLETED 를 포함해서 테스트마다 같은 키로 완료 실행을 만들면 부딪힌다.
     */
    private static RunPlan plannedVersions() {
        Map<String, Object> versions = new LinkedHashMap<>();
        versions.put("mapping", "SECTOR-ETF-2026-09");
        versions.put("rules", Map.of("version", "1", "provisional", false));
        versions.put("costProfile", Map.of("version", "PROVISIONAL-DEMO-1", "provisional", true));
        versions.put("data", "FIXTURE-three_etf_24m");
        versions.put("engine", "nautilus_trader 1.219.0 / worker 2.1.0");
        versions.put("evaluationSplit", "SPLIT-1");
        return new RunPlan(null, null, null, null, null, null, null, null, versions, null);
    }

    /** 사용자가 호출하는 식별자는 <b>요청</b>의 것이다(R-02). 실행을 만들고 그것을 가리키는 요청을 붙여 그 식별자를 돌려준다. */
    private String requestFor(BacktestRun run) {
        return requestRepository
                .save(
                        BacktestRequest.create(
                                run.getId(),
                                requesterId,
                                ExecutionType.SINGLE,
                                ResultSource.NEW,
                                null,
                                PublicResultToken.generate(),
                                null))
                .getBacktestId()
                .value();
    }

    private long requesterId;

    @BeforeEach
    void createRequester() {
        requesterId = transactionTemplate.execute(status -> newUser());
    }

    /**
     * 결과 저장은 <b>한 트랜잭션 안에서</b> 해야 한다 — 결과 저장 포트의 계약이고, Job을 {@code getReferenceById} 프록시로 참조하기
     * 때문이다. 운영에서는 {@code @Transactional} 서비스가 그 경계를 만든다.
     */
    private BacktestRun saveResult(BacktestEvent<FinishedPayload> event, boolean completed) {
        return transactionTemplate.execute(
                status -> {
                    BacktestRun job = newJob();
                    resultRepository.save(
                            new SaveBacktestResultCommand(
                                    job.getId(),
                                    event.executionId(),
                                    event.eventId(),
                                    event.payload().engineOutput(),
                                    null));
                    return jobRepository.update(
                            completed
                                    ? job.markCompleted()
                                    : job.markFailed("DATA_INSUFFICIENT", "no data"));
                });
    }

    private BacktestRun completedJob() {
        return saveResult(MessageFixtures.finishedCompleted(), true);
    }

    private BacktestRun failedJob() {
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
                mockMvc.perform(get(url).with(as(requesterId)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return objectMapper.readTree(body).get("data");
    }

    @Test
    @DisplayName("상세 응답이 계약 예시와 같다")
    void detailMatchesExample() throws Exception {
        String id = requestFor(completedJob());

        JsonNode actual = actualData("/backtests/" + id);
        JsonNode expected = expectedData("backtest-detail.json", id);
        // 접수 시각은 실행할 때마다 다르다. 예시가 고정할 수 있는 값이 아니므로 실제 값을 옮겨 담고
        // 나머지 전부를 비교한다 — "있다/없다"와 타입은 그대로 지켜진다.
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected)
                .set("queuedAt", actual.get("queuedAt"));

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("거래 응답이 계약 예시와 같다 — entryDate 변환과 OPEN의 명시적 null 포함")
    void tradesMatchExample() throws Exception {
        String id = requestFor(completedJob());

        assertThat(actualData("/backtests/" + id + "/trades"))
                .isEqualTo(expectedData("backtest-trades.json", id));
    }

    @Test
    @DisplayName("비중 응답이 계약 예시와 같다 — weightsSource 와 리밸런싱 포인트")
    void weightsMatchExample() throws Exception {
        String id = requestFor(completedJob());

        assertThat(actualData("/backtests/" + id + "/weights"))
                .isEqualTo(expectedData("backtest-weights.json", id));
    }

    @Test
    @DisplayName("예외 응답이 계약 예시와 같다")
    void exceptionsMatchExample() throws Exception {
        String id = requestFor(completedJob());

        assertThat(actualData("/backtests/" + id + "/exceptions"))
                .isEqualTo(expectedData("backtest-exceptions.json", id));
    }

    @Test
    @DisplayName("보유 응답이 계약 예시와 같다 — 직전 스냅샷 규칙")
    void positionsMatchExample() throws Exception {
        String id = requestFor(completedJob());
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
        String id = requestFor(completedJob());
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
        String id = requestFor(transactionTemplate.execute(status -> newJob()));

        for (String path :
                List.of(
                        "/timeseries",
                        "/trades",
                        "/positions?date=2025-01-02",
                        "/weights",
                        "/exceptions")) {
            mockMvc.perform(get("/backtests/" + id + path).with(as(requesterId)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("RESULT_NOT_READY"));
        }
        // 상세는 200이고 config만 있다
        mockMvc.perform(get("/backtests/" + id).with(as(requesterId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.config.strategyId").value("MA_CROSSOVER"))
                .andExpect(jsonPath("$.data.summary").doesNotExist());
    }

    @Test
    @DisplayName("FAILED는 상세가 200 + error이고 하위 리소스는 409 BACKTEST_FAILED다")
    void failedJobExposesErrorButNotSubResources() throws Exception {
        String id = requestFor(failedJob());

        mockMvc.perform(get("/backtests/" + id).with(as(requesterId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failure.code").value("DATA_INSUFFICIENT"))
                .andExpect(jsonPath("$.data.summary").doesNotExist());

        for (String path :
                List.of(
                        "/timeseries",
                        "/trades",
                        "/positions?date=2025-01-02",
                        "/weights",
                        "/exceptions")) {
            mockMvc.perform(get("/backtests/" + id + path).with(as(requesterId)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("BACKTEST_FAILED"));
        }
    }

    @Test
    @DisplayName("COMPLETED인데 결과 행이 없으면 네 엔드포인트 모두 500이다 — 거래 목록도 내보내지 않는다")
    void missingResultRowIsServerErrorEverywhere() throws Exception {
        BacktestRun job = completedJob();
        String id = requestFor(job);
        // 결과 행만 지운다. 거래 행은 남겨 둔다 — /trades가 그것만 보고 200을 주면 안 된다
        jdbcTemplate.update("DELETE FROM backtest_result WHERE run_id = ?", job.getId());
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM backtest_trade WHERE run_id = ?",
                                Long.class,
                                job.getId()))
                .isEqualTo(3);

        for (String path :
                List.of(
                        "",
                        "/timeseries",
                        "/trades",
                        "/positions?date=2025-01-22",
                        "/weights",
                        "/exceptions")) {
            mockMvc.perform(get("/backtests/" + id + path).with(as(requesterId)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
        }
    }

    @Test
    @DisplayName("시계열 응답은 gzip으로 압축된다 — 이 앱에서 가장 큰 응답이다")
    void timeseriesIsGzipped() {
        String id = requestFor(completedJob());
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

        for (String path :
                List.of(
                        "",
                        "/timeseries",
                        "/trades",
                        "/positions?date=2025-01-02",
                        "/weights",
                        "/exceptions")) {
            mockMvc.perform(get("/backtests/" + missing + path).with(as(requesterId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        }
    }
}
