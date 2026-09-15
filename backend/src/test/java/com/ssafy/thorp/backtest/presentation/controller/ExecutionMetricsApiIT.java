package com.ssafy.thorp.backtest.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.application.service.BacktestEventService;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.model.RunLifecycle;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.backtest.infrastructure.messaging.MessageFixtures;
import com.ssafy.thorp.backtest.presentation.support.OperatorGate;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code GET /internal/execution-metrics}(spec 004 R-10, quickstart S10). 운영자 문과 "API = 뷰"를 실제 DB 로
 * 본다.
 *
 * <p>스위트가 컨테이너를 공유하므로 절대값 대신 뷰와의 일치와 방향(p50 ≤ p95 ≤ p99)만 단언한다.
 */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.host=localhost",
            "spring.rabbitmq.port=1",
            "spring.rabbitmq.connection-timeout=1s",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "thorp.backtest.republish.enabled=false",
            "thorp.backtest.liveness.interval=PT1H",
            "thorp.ops.token=it-ops-token",
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
@AutoConfigureMockMvc
class ExecutionMetricsApiIT extends PostgresTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private BacktestEventService events;
    @Autowired private BacktestRunRepository runRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;

    private void oneCompletedSingleRun() {
        BacktestRun run =
                transactionTemplate.execute(
                        status ->
                                runRepository.save(
                                        BacktestRun.create(
                                                        "MA_CROSSOVER",
                                                        "1.0.0",
                                                        Map.of("shortWindow", 20),
                                                        List.of(
                                                                new InstrumentId("005930.XKRX"),
                                                                new InstrumentId("000660.XKRX")),
                                                        new BacktestPeriod(
                                                                LocalDate.of(2025, 1, 1),
                                                                LocalDate.of(2025, 6, 30)),
                                                        new BigDecimal("10000000"),
                                                        PositionSizingMode.STRATEGY_DEFINED,
                                                        new BigDecimal("0.50"),
                                                        new InstrumentId("069500.XKRX"),
                                                        42)
                                                .withLifecycle(
                                                        RunLifecycle.queuedAt(clock.instant())
                                                                .publishAttempted())));
        String runId = run.getBacktestId().value();
        events.handleStarted(
                MessageFixtures.parse(
                        MessageFixtures.withRunId(MessageFixtures.STARTED, runId),
                        MessageFixtures.STARTED_EVENT));
        events.handleFinished(
                MessageFixtures.parse(
                        MessageFixtures.withRunId(MessageFixtures.FINISHED_COMPLETED, runId),
                        MessageFixtures.FINISHED_EVENT));
    }

    @Test
    @DisplayName("헤더가 없거나 틀리면 404 — 운영자 경로는 존재를 알려 주지 않는다")
    void requiresTheOperatorToken() throws Exception {
        mockMvc.perform(get("/internal/execution-metrics"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/internal/execution-metrics").header(OperatorGate.HEADER, "wrong"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("유형별 집계가 뷰 v_run_metric_summary 와 같고 분위수는 p50 ≤ p95 ≤ p99 다 (S10)")
    void summaryMatchesTheView() throws Exception {
        oneCompletedSingleRun();

        String body =
                mockMvc.perform(
                                get("/internal/execution-metrics")
                                        .param("type", "SINGLE")
                                        .header(OperatorGate.HEADER, "it-ops-token"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.success").value(true))
                        .andExpect(jsonPath("$.data.rows[0].executionType").value("SINGLE"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode row = objectMapper.readTree(body).path("data").path("rows").get(0);
        Map<String, Object> view =
                jdbcTemplate.queryForMap(
                        "SELECT * FROM v_run_metric_summary WHERE execution_type = 'SINGLE'");

        assertThat(row.path("count").asLong()).isEqualTo(((Number) view.get("count")).longValue());
        assertThat(row.path("reuseCount").asLong())
                .isEqualTo(((Number) view.get("reuse_count")).longValue());
        assertThat(row.path("limitHitCount").asLong())
                .isEqualTo(((Number) view.get("limit_hit_count")).longValue());
        // 비율·분위수는 문자열 decimal 로 나간다(다른 응답과 같은 표기).
        assertThat(row.path("failRate").isTextual()).isTrue();
        BigDecimal p50 = new BigDecimal(row.path("waitP50Ms").asText());
        BigDecimal p95 = new BigDecimal(row.path("waitP95Ms").asText());
        BigDecimal p99 = new BigDecimal(row.path("waitP99Ms").asText());
        assertThat(p95).isGreaterThanOrEqualTo(p50);
        assertThat(p99).isGreaterThanOrEqualTo(p95);
        assertThat(new BigDecimal(row.path("flexServedRate").asText()))
                .isBetween(BigDecimal.ZERO, BigDecimal.ONE);
    }

    @Test
    @DisplayName("from·to 는 recorded_at 반열림 구간으로 자른다 — 그 구간의 측정·한도 도달만 센다")
    void rangeFiltersByRecordedAt() throws Exception {
        oneCompletedSingleRun();
        // 방금 기록된 행의 시각을 기준으로 1초 창을 만든다. 공유 DB 의 잔여물은 창 밖이거나, 안에 있으면 DB 셈에도 함께 든다.
        java.time.Instant from =
                jdbcTemplate
                        .queryForObject(
                                "SELECT max(recorded_at) FROM backtest_run_metric"
                                        + " WHERE execution_type = 'SINGLE'",
                                java.time.OffsetDateTime.class)
                        .toInstant();
        java.time.Instant to = from.plusSeconds(1);

        String body =
                mockMvc.perform(
                                get("/internal/execution-metrics")
                                        .param("type", "SINGLE")
                                        .param("from", from.toString())
                                        .param("to", to.toString())
                                        .header(OperatorGate.HEADER, "it-ops-token"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode row = objectMapper.readTree(body).path("data").path("rows").get(0);

        Long expectedCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM backtest_run_metric WHERE execution_type = 'SINGLE'"
                                + " AND recorded_at >= ? AND recorded_at < ?",
                        Long.class,
                        java.time.OffsetDateTime.ofInstant(from, java.time.ZoneOffset.UTC),
                        java.time.OffsetDateTime.ofInstant(to, java.time.ZoneOffset.UTC));
        Long expectedHits =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM backtest_limit_hit WHERE execution_type = 'SINGLE'"
                                + " AND at >= ? AND at < ?",
                        Long.class,
                        java.time.OffsetDateTime.ofInstant(from, java.time.ZoneOffset.UTC),
                        java.time.OffsetDateTime.ofInstant(to, java.time.ZoneOffset.UTC));
        assertThat(row.path("count").asLong()).isEqualTo(expectedCount).isGreaterThanOrEqualTo(1L);
        assertThat(row.path("limitHitCount").asLong()).isEqualTo(expectedHits);
        // 전체 기간(뷰)보다 작거나 같다 — 창이 전체를 덮는 일은 없다.
        Long viewCount =
                jdbcTemplate.queryForObject(
                        "SELECT count FROM v_run_metric_summary WHERE execution_type = 'SINGLE'",
                        Long.class);
        assertThat(row.path("count").asLong()).isLessThanOrEqualTo(viewCount);
    }

    @Test
    @DisplayName("기간을 거꾸로 주면 400, 미래 기간이면 빈 목록 — 0 으로 채운 행을 만들지 않는다")
    void rangeValidationAndEmptyRange() throws Exception {
        mockMvc.perform(
                        get("/internal/execution-metrics")
                                .param("from", "2026-09-16T00:00:00Z")
                                .param("to", "2026-09-15T00:00:00Z")
                                .header(OperatorGate.HEADER, "it-ops-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mockMvc.perform(
                        get("/internal/execution-metrics")
                                .param("from", "2099-01-01T00:00:00Z")
                                .param("to", "2099-01-02T00:00:00Z")
                                .header(OperatorGate.HEADER, "it-ops-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows").isEmpty());
    }
}
