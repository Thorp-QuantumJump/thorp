package com.ssafy.thorp.result.presentation.controller;

import static com.ssafy.thorp.support.Requesters.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import com.ssafy.thorp.backtest.application.command.SaveBacktestResultCommand;
import com.ssafy.thorp.backtest.application.repository.BacktestRequestRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestResultRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestReuseEventRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.model.ProgressStage;
import com.ssafy.thorp.backtest.domain.model.PublicResultToken;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.model.RunPlan;
import com.ssafy.thorp.backtest.domain.model.RunProgress;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.backtest.infrastructure.messaging.MessageFixtures;
import com.ssafy.thorp.result.application.repository.ExpiredPublicTokenRepository;
import com.ssafy.thorp.result.application.service.PublicResultExpirySweeper;
import com.ssafy.thorp.support.PortMockedIntegrationTestSupport;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 공개 열람 경로를 실제 DB로 호출한다(spec 005 T025, quickstart 1·7·8).
 *
 * <p><b>응답이 {@code docs/specs/result-api/examples/}의 예시와 필드 단위로 같은지 단언한다.</b> 예시는 B03 화면 테스트의
 * 픽스처이기도 하다(T039) — 계약·서버·화면이 같은 파일 하나로 묶인다. 실행마다 달라지는 값(토큰·시각)만 실제 값으로 옮겨 담고 나머지는 전부 비교하므로, 필드의
 * 있음·없음과 타입은 그대로 지켜진다.
 *
 * <p>결과 저장은 {@code BacktestResultApiIT}와 같은 메시징 픽스처를 쓴다. 두 IT가 같은 결과를 두 경로로 읽으므로, 공개 예시와 004 예시가
 * 어긋나면 한쪽이 깨진다.
 *
 * <p>만료 정리 작업은 끈다. 만료 시각을 과거로 돌린 행을 스케줄러가 먼저 지우면 410 대신 404가 나와 시험이 우연에 기댄다. 정리는 {@code
 * PublicResultExpirySweeperIT}가 따로 본다.
 */
class ResultApiIT extends PortMockedIntegrationTestSupport {

    /** gradle test의 작업 디렉터리는 backend/다. */
    private static final Path EXAMPLES = Path.of("..", "docs", "specs", "result-api", "examples");

    private static final Path REQUEST_FIXTURE =
            Path.of("..", "docs", "specs", "backtest-api", "examples", "backtest-request.json");

    private static final List<String> SUB_RESOURCES =
            List.of(
                    "/timeseries",
                    "/trades",
                    "/positions?date=2025-01-22",
                    "/weights",
                    "/exceptions");

    @Autowired private MockMvc mockMvc;
    @Autowired private BacktestRunRepository runRepository;
    @Autowired private BacktestRequestRepository requestRepository;
    @Autowired private BacktestResultRepository resultRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private BacktestReuseEventRepository reuseEventRepository;
    @Autowired private ExpiredPublicTokenRepository expiredTokens;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private long requesterId;

    @BeforeEach
    void createRequester() {
        requesterId = transactionTemplate.execute(status -> newUser());
    }

    private long newUser() {
        String suffix = Long.toString(System.nanoTime() % 1_000_000_000L);
        String email = "public-result-" + suffix + "@example.com";
        jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, nickname, phone_number, status,"
                        + " paper_ranking_enabled, live_ranking_enabled, ranking_display_nickname,"
                        + " created_at, updated_at)"
                        + " VALUES (?, 'x', ?, ?, 'ACTIVE', false, false, false, NOW(), NOW())",
                email,
                "p" + suffix,
                suffix);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    /**
     * {@code BacktestResultApiIT}와 같은 결과를 붙일 실행. 요청 사본이 없어 {@code config}는 실행 컬럼으로 채워진다. 전략은 등록표에
     * 있는 것이라 {@code strategyName}이 채워진다.
     */
    private BacktestRun newRun() {
        Map<String, Object> versions = new LinkedHashMap<>();
        versions.put("mapping", "SECTOR-ETF-2026-09");
        versions.put("rules", Map.of("version", "1", "provisional", false));
        versions.put("costProfile", Map.of("version", "PROVISIONAL-DEMO-1", "provisional", true));
        versions.put("data", "FIXTURE-three_etf_24m");
        versions.put("engine", "nautilus_trader 1.219.0 / worker 2.3.0 / uv.lock 8e8a6be0a6a8");
        versions.put("evaluationSplit", "SPLIT-1");
        return runRepository.save(
                BacktestRun.create(
                                "EQUAL_WEIGHT",
                                "1.0.0",
                                Map.of("rebalancePeriod", "MONTHLY"),
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
                        .withPlan(
                                new RunPlan(
                                        null, null, null, null, null, null, null, null, versions,
                                        null)));
    }

    private static BacktestRun started(BacktestRun run) {
        return run.withLifecycle(run.getLifecycle().started(Instant.now()));
    }

    private BacktestRun completedRun() {
        return transactionTemplate.execute(
                status -> {
                    BacktestRun run = newRun();
                    var event = MessageFixtures.finishedCompleted();
                    resultRepository.save(
                            new SaveBacktestResultCommand(
                                    run.getId(),
                                    event.executionId(),
                                    event.eventId(),
                                    event.payload().engineOutput(),
                                    null));
                    return runRepository.update(run.markCompleted());
                });
    }

    /** 비로그인 요청. 만료 시각은 하루 뒤다. */
    private String anonymousTokenFor(BacktestRun run) {
        return tokenFor(run, null, Instant.now().plus(1, ChronoUnit.DAYS));
    }

    private String tokenFor(BacktestRun run, Long requester, Instant expiresAt) {
        PublicResultToken token = PublicResultToken.generate();
        requestRepository.save(
                BacktestRequest.create(
                        run.getId(),
                        requester,
                        ExecutionType.SINGLE,
                        ResultSource.NEW,
                        null,
                        token,
                        expiresAt));
        return token.value();
    }

    private String body(MockHttpServletRequestBuilder request, int expectedStatus)
            throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private JsonNode example(String file) throws Exception {
        return objectMapper.readTree(Files.readString(EXAMPLES.resolve(file)));
    }

    /** 실행마다 달라지는 값만 실제 응답에서 예시로 옮긴다. 나머지는 예시 그대로 비교된다. */
    private static JsonNode withVolatile(JsonNode expected, JsonNode actual, String... fields) {
        ObjectNode data = (ObjectNode) expected.get("data");
        for (String field : fields) {
            data.set(field, actual.get("data").get(field));
        }
        return expected;
    }

    /** 비로그인 접수로 만든 요청. 실행 행은 접수 경로가 만든 것이라 요청 사본({@code config})이 실제 화면이 받는 모양이다. */
    private record Accepted(String token, BacktestRun run) {}

    /**
     * 계약 예시 요청을 비로그인으로 접수한다. 동일 버전 키가 시작 금액을 포함하므로 예시마다 다른 고정 금액을 준다 — 금액이 고정이어야 예시의 {@code
     * config}가 실행마다 같다.
     */
    private Accepted acceptAnonymously(String initialCash) throws Exception {
        String requestBody = Files.readString(REQUEST_FIXTURE).replace("10000000.00", initialCash);
        String accepted =
                body(
                        post("/backtests")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody),
                        202);
        String backtestId = JsonPath.read(accepted, "$.data.backtestId");
        BacktestRequest request = requestRepository.findByBacktestId(backtestId).orElseThrow();
        return new Accepted(
                JsonPath.read(accepted, "$.data.publicToken"),
                runRepository.findById(request.getRunId()).orElseThrow());
    }

    @Test
    @DisplayName("비로그인 접수가 완료되면 토큰으로 열리고 상세·시계열이 예시와 같다 (quickstart 1)")
    void completedResultMatchesExamples() throws Exception {
        Accepted accepted = acceptAnonymously("11110000.00");
        transactionTemplate.executeWithoutResult(
                status -> {
                    BacktestRun run = accepted.run();
                    var event = MessageFixtures.finishedCompleted();
                    resultRepository.save(
                            new SaveBacktestResultCommand(
                                    run.getId(),
                                    event.executionId(),
                                    event.eventId(),
                                    event.payload().engineOutput(),
                                    null));
                    runRepository.update(
                            started(run.markRunning("exec-public-done"))
                                    .markCompleted()
                                    .withLifecycle(
                                            run.getLifecycle()
                                                    .started(Instant.now())
                                                    .finished(Instant.now())));
                });
        String token = accepted.token();

        JsonNode detail = objectMapper.readTree(body(get("/results/" + token), 200));
        assertThat(detail)
                .isEqualTo(
                        withVolatile(
                                example("result-detail-completed.json"),
                                detail,
                                "publicToken",
                                "expiresAt",
                                "queuedAt",
                                "startedAt",
                                "finishedAt"));
        assertThat(detail.at("/data/publicToken").asText()).isEqualTo(token);
        assertThat(detail.at("/data/expiresAt").isNull()).isFalse();
        assertThat(detail.at("/data/startedAt").isNull()).isFalse();

        JsonNode timeseries =
                objectMapper.readTree(body(get("/results/" + token + "/timeseries"), 200));
        assertThat(timeseries).isEqualTo(example("result-timeseries.json"));
    }

    @Test
    @DisplayName("계산 중인 결과는 진행 상태를 담고 예시와 같다")
    void runningResultMatchesExample() throws Exception {
        Accepted accepted = acceptAnonymously("12220000.00");
        transactionTemplate.executeWithoutResult(
                status ->
                        runRepository.update(
                                started(accepted.run().markRunning("exec-public-1"))
                                        .recordProgress(
                                                new RunProgress(
                                                        ProgressStage.COMPUTING,
                                                        LocalDate.of(2024, 3, 29),
                                                        180,
                                                        380))));

        JsonNode detail = objectMapper.readTree(body(get("/results/" + accepted.token()), 200));
        assertThat(detail)
                .isEqualTo(
                        withVolatile(
                                example("result-detail-running.json"),
                                detail,
                                "publicToken",
                                "expiresAt",
                                "queuedAt",
                                "startedAt",
                                "queueWaitSeconds"));
    }

    @Test
    @DisplayName(
            "시작 전에 취소된 결과는 요약 없이 이유를 담고 예시와 같다 — 볼 결과가 없어 weightsSource·exceptionKinds 도 null 이다")
    void cancelledBeforeStartMatchesExample() throws Exception {
        Accepted accepted = acceptAnonymously("13330000.00");
        transactionTemplate.executeWithoutResult(
                status -> {
                    BacktestRun run = accepted.run();
                    runRepository.update(
                            run.markCancelled(null)
                                    .withLifecycle(run.getLifecycle().finished(Instant.now())));
                });

        JsonNode detail = objectMapper.readTree(body(get("/results/" + accepted.token()), 200));
        assertThat(detail)
                .isEqualTo(
                        withVolatile(
                                example("result-detail-cancelled-no-summary.json"),
                                detail,
                                "publicToken",
                                "expiresAt",
                                "queuedAt",
                                "finishedAt"));
    }

    @Test
    @DisplayName("응답 본문 어디에도 BT- 식별자가 없다 — 성공 응답과 004 오류 문구 모두 (quickstart 8, SC-009)")
    void noInternalIdentifierLeaks() throws Exception {
        String completed = anonymousTokenFor(completedRun());
        assertThat(body(get("/results/" + completed), 200)).doesNotContain("BT-");
        for (String path : SUB_RESOURCES) {
            assertThat(body(get("/results/" + completed + path), 200))
                    .as(path)
                    .doesNotContain("BT-")
                    .doesNotContain("backtestId");
        }

        // 004 예외 메시지에는 backtestId=BT-… 가 들어 있다. 공개 경로의 409 에는 나오면 안 된다.
        String queued = anonymousTokenFor(transactionTemplate.execute(status -> newRun()));
        for (String path : SUB_RESOURCES) {
            String conflict = body(get("/results/" + queued + path), 409);
            assertThat(JsonPath.<String>read(conflict, "$.error.code"))
                    .isEqualTo("RESULT_NOT_READY");
            assertThat(conflict).as(path).doesNotContain("BT-");
        }
    }

    @Test
    @DisplayName("토큰 뒷자리를 바꾸면 404, 형식이 틀리면 400 이다 (SC-009)")
    void tamperedOrMalformedTokenIsRejected() throws Exception {
        String token = anonymousTokenFor(completedRun());
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == '0' ? '1' : '0');

        mockMvc.perform(get("/results/" + tampered))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/results/" + token.toUpperCase()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mockMvc.perform(get("/results/BT-20260911-000001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("만료 시각이 지나면 상세·하위 자원 모두 410 이고 본문이 예시와 같다 (quickstart 7, SC-008)")
    void expiredResultIsGone() throws Exception {
        String token = anonymousTokenFor(completedRun());
        jdbcTemplate.update(
                "UPDATE backtest_request SET public_token_expires_at = NOW() - INTERVAL '1 hour'"
                        + " WHERE public_token = ?",
                token);

        JsonNode gone = objectMapper.readTree(body(get("/results/" + token), 410));
        assertThat(gone).isEqualTo(example("result-expired.json"));
        for (String path : SUB_RESOURCES) {
            mockMvc.perform(get("/results/" + token + path))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.error.code").value("RESULT_EXPIRED"));
        }
    }

    @Test
    @DisplayName("정리 작업이 만료된 요청을 지운 뒤에도 그 주소는 404 가 아니라 410 이고 본문이 예시와 같다 (T087, SC-008)")
    void expiredResultStaysGoneAfterSweep() throws Exception {
        String token = anonymousTokenFor(completedRun());
        jdbcTemplate.update(
                "UPDATE backtest_request SET public_token_expires_at = NOW() - INTERVAL '1 hour'"
                        + " WHERE public_token = ?",
                token);

        new PublicResultExpirySweeper(
                        requestRepository,
                        reuseEventRepository,
                        expiredTokens,
                        transactionManager,
                        Clock.fixed(Instant.now(), ZoneOffset.UTC),
                        1000,
                        Duration.ofDays(30))
                .sweep();

        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM backtest_request WHERE public_token = ?",
                                Long.class,
                                token))
                .as("요청 행은 지워졌다")
                .isZero();
        assertThat(objectMapper.readTree(body(get("/results/" + token), 410)))
                .isEqualTo(example("result-expired.json"));
        mockMvc.perform(get("/results/" + token + "/timeseries"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("RESULT_EXPIRED"));
        // 한 번도 발급되지 않은 토큰은 여전히 404 다.
        mockMvc.perform(get("/results/" + PublicResultToken.generate().value()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("로그인 사용자의 결과도 같은 토큰으로 열리고 만료가 없다")
    void loggedInResultOpensWithoutExpiry() throws Exception {
        String token = tokenFor(completedRun(), requesterId, null);

        mockMvc.perform(get("/results/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.expiresAt").value((Object) null));
    }

    @Test
    @DisplayName("비로그인 접수의 토큰으로 곧바로 열리고, 실패한 뒤 다시 시도하면 새 토큰과 원 토큰이 온다")
    void anonymousIntakeOpensAndRetriesByToken() throws Exception {
        long unique = 10_000_000L + (System.nanoTime() % 1_000_000L);
        String requestBody =
                Files.readString(REQUEST_FIXTURE).replace("10000000.00", unique + ".00");
        String accepted =
                body(
                        post("/backtests")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody),
                        202);
        String token = JsonPath.read(accepted, "$.data.publicToken");

        String detail = body(get("/results/" + token), 200);
        assertThat(JsonPath.<String>read(detail, "$.data.status")).isEqualTo("QUEUED");
        assertThat(detail).doesNotContain("BT-");

        // 실패로 끝났다고 친다. 재시도는 저장된 요청 사본으로 접수를 다시 탄다.
        String backtestId = JsonPath.read(accepted, "$.data.backtestId");
        transactionTemplate.executeWithoutResult(
                status -> {
                    BacktestRequest request =
                            requestRepository.findByBacktestId(backtestId).orElseThrow();
                    BacktestRun run = runRepository.findById(request.getRunId()).orElseThrow();
                    runRepository.update(run.markFailed("DATA_INSUFFICIENT", "no data"));
                });

        String retried = body(post("/results/" + token + "/retry").with(csrf()), 202);

        String newToken = JsonPath.read(retried, "$.data.publicToken");
        assertThat(newToken).matches("^[0-9a-f]{32}$").isNotEqualTo(token);
        assertThat(JsonPath.<String>read(retried, "$.data.retryOfToken")).isEqualTo(token);
        assertThat(retried).doesNotContain("BT-");
        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        "SELECT requester_id, public_token_expires_at FROM backtest_request"
                                + " WHERE public_token = ?",
                        newToken);
        assertThat(row.get("requester_id")).as("비로그인 재시도는 요청자가 없다").isNull();
        assertThat(row.get("public_token_expires_at")).isNotNull();
    }

    @Test
    @DisplayName("로그인 사용자가 남의 공개 결과를 다시 시도하면 새 요청은 원 요청자가 아니라 호출한 사람의 것이다")
    void retryBelongsToCaller() throws Exception {
        long owner = transactionTemplate.execute(status -> newUser());
        long unique = 20_000_000L + (System.nanoTime() % 1_000_000L);
        String requestBody =
                Files.readString(REQUEST_FIXTURE).replace("10000000.00", unique + ".00");
        String accepted =
                body(
                        post("/backtests")
                                .with(csrf())
                                .with(as(owner))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody),
                        202);
        String token = JsonPath.read(accepted, "$.data.publicToken");
        String backtestId = JsonPath.read(accepted, "$.data.backtestId");
        transactionTemplate.executeWithoutResult(
                status -> {
                    BacktestRequest request =
                            requestRepository.findByBacktestId(backtestId).orElseThrow();
                    BacktestRun run = runRepository.findById(request.getRunId()).orElseThrow();
                    runRepository.update(run.markFailed("DATA_INSUFFICIENT", "no data"));
                });

        String retried =
                body(post("/results/" + token + "/retry").with(csrf()).with(as(requesterId)), 202);

        Long stored =
                jdbcTemplate.queryForObject(
                        "SELECT requester_id FROM backtest_request WHERE public_token = ?",
                        Long.class,
                        JsonPath.<String>read(retried, "$.data.publicToken"));
        assertThat(stored).isEqualTo(requesterId).isNotEqualTo(owner);
    }
}
