package com.ssafy.thorp.myrecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.application.service.BacktestService;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.macro.support.MacroRegistrySeed;
import com.ssafy.thorp.myrecord.application.service.MyRecordRegistrar;
import com.ssafy.thorp.myrecord.domain.vo.EntryPath;
import com.ssafy.thorp.paramsearch.SearchableStrategies;
import com.ssafy.thorp.paramsearch.application.command.CreateParamSearchCommand;
import com.ssafy.thorp.paramsearch.application.port.CandidateJobLauncher;
import com.ssafy.thorp.paramsearch.application.result.ParamSearchResult;
import com.ssafy.thorp.paramsearch.application.service.ParamSearchService;
import com.ssafy.thorp.regime.domain.vo.BaselineOrigin;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.support.LauncherMockedIntegrationTestSupport;
import com.ssafy.thorp.support.Requesters;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 내보내기(spec 006 T044, FR-015·FR-016, quickstart S8).
 *
 * <p>보는 것: 구획이 계약대로 있는지, {@code exclusions}가 늘 있는지, 금지 필드가 <b>키째로</b> 없는지, {@code fills}가 기록한 결과에만
 * 있는지, CSV 가 BOM·CRLF 인지, 상태별(진행 중 409·실패는 설정과 사유만) 규칙과 남의 기록 404.
 *
 * <p>탐색 묶음을 접수 경로로 만들기 위해 후보 실행기를 목으로 바꾼 기반을 쓴다. <b>기간을 다른 IT와 겹치지 않게 둔다</b> — 본문이 같으면 동일 버전 키도 같아
 * 다른 IT가 만든 실행에 합류한다.
 */
class MyRecordExportIT extends LauncherMockedIntegrationTestSupport {

    private static final String REGIME_KEY =
            "FIELD:EXPORT:2026-06-01:FIXTURE-20260915:FIXTURE-REG-1:REGIME-V1";
    private static final List<String> UNIVERSE = List.of("091160.XKRX", "305720.XKRX");

    @Autowired private MockMvc mockMvc;
    @Autowired private BacktestService backtestService;
    @Autowired private ParamSearchService paramSearchService;
    @Autowired private ParameterRegistryPort registry;
    @Autowired private MyRecordRegistrar registrar;
    @Autowired private CandidateJobLauncher launcher;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    private final AtomicLong nextRunId = new AtomicLong(940_000L);

    @BeforeEach
    void prepare() {
        Mockito.when(launcher.launchCandidate(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenAnswer(invocation -> insertStubRun());
        Mockito.when(launcher.launchBaseline(ArgumentMatchers.any()))
                .thenAnswer(invocation -> insertStubRun());
        // 010 은 008 등록표에서 지표 이름을 읽는다(T070). 비어 있으면 조회가 503 이다.
        MacroRegistrySeed.seedRegistry(jdbc);
    }

    @Test
    @DisplayName("완료한 분석의 JSON 은 계약의 구획을 모두 담고 금지 필드는 키째로 없다")
    void 완료한_분석의_JSON() throws Exception {
        long owner = newUser("json");
        long runId = completedRun(owner, LocalDate.of(2024, 5, 6), true);
        String recordId = recordIdOf(owner);
        String recordName =
                jdbc.queryForObject(
                        "SELECT name FROM my_record WHERE record_id = ?", String.class, recordId);

        MvcResult result =
                mockMvc.perform(export(recordId, "json").with(Requesters.as(owner)))
                        .andExpect(status().isOk())
                        .andExpect(
                                header().string(
                                                "Content-Type",
                                                Matchers.startsWith("application/json")))
                        // 파일 이름은 기록 이름 그대로다 — record 구획의 키가 바뀌어 "null.json" 이 되면 여기서 잡힌다.
                        .andExpect(
                                header().string(
                                                "Content-Disposition",
                                                ContentDisposition.attachment()
                                                        .filename(
                                                                recordName + ".json",
                                                                StandardCharsets.UTF_8)
                                                        .build()
                                                        .toString()))
                        .andReturn();
        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .startsWith("attachment")
                .contains("filename*=UTF-8''");
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode json = objectMapper.readTree(body);

        assertThat(fieldNames(json))
                .containsExactly(
                        "record",
                        "config",
                        "referenceVersions",
                        "summary",
                        "timeseries",
                        "trades",
                        "fills",
                        "exceptions",
                        "exclusions");
        assertThat(json.at("/record/recordId").asText()).isEqualTo(recordId);
        assertThat(json.at("/record/status").asText()).isEqualTo("COMPLETED");
        // 전략 파라미터·시나리오는 별도 축이다(FR-013).
        assertThat(json.at("/config/parameters/rebalance").asText()).isEqualTo("MONTH_END");
        assertThat(json.at("/config/scenario").asText()).isEqualTo("FULL_PERIOD");
        assertThat(json.at("/referenceVersions/sectorEtfMapVersion").asText())
                .isEqualTo("SECTOR-ETF-2026-09");
        assertThat(json.at("/summary/netTotalReturn").decimalValue())
                .isEqualByComparingTo("0.1200");
        assertThat(json.get("timeseries")).hasSize(1);
        assertThat(json.at("/trades/0/tradeId").asText()).isEqualTo("T-" + runId);
        assertThat(json.at("/fills/0/fillId").asText()).isEqualTo("F-" + runId);
        assertThat(json.at("/exceptions/0/kind").asText()).isEqualTo("ZERO_VOLATILITY");

        List<String> excluded = json.get("exclusions").findValuesAsText("item");
        assertThat(excluded).contains("dailyPriceRaw", "newsArticleBody");
        assertThat(json.at("/exclusions/0/reason").asText())
                .isEqualTo("SOURCE_REDISTRIBUTION_FORBIDDEN");
        assertThat(json.at("/exclusions/0/message").asText()).isNotBlank();
        // 원천 원값·기사 본문·운영 정보는 자리가 없다 — 값이 빈 키도 없어야 한다(R10, S8-4·S8-8).
        assertThat(body)
                .doesNotContain("\"dailyPriceRaw\":")
                .doesNotContain("\"articleBody\"")
                .doesNotContain("\"rawPrices\"")
                .doesNotContain("\"worker\"")
                .doesNotContain("\"dependencies\"");
    }

    @Test
    @DisplayName("체결을 기록하지 않은 결과에는 fills 키가 아예 없고 exclusions 가 그 사실을 적는다(원칙 VI)")
    void 체결_미기록은_키가_없다() throws Exception {
        long owner = newUser("nofill");
        completedRun(owner, LocalDate.of(2024, 5, 7), false);

        JsonNode json = exportJson(owner, recordIdOf(owner));

        assertThat(json.has("fills")).isFalse();
        assertThat(json.has("trades")).isTrue();
        assertThat(json.get("exclusions").findValuesAsText("item"))
                .contains("dailyPriceRaw", "newsArticleBody", "fills");
    }

    @Test
    @DisplayName("CSV 는 BOM 으로 시작하고 CRLF 를 쓰며 구획마다 # 이름 줄이 있다")
    void CSV() throws Exception {
        long owner = newUser("csv");
        completedRun(owner, LocalDate.of(2024, 5, 8), true);

        MvcResult result =
                mockMvc.perform(export(recordIdOf(owner), "csv").with(Requesters.as(owner)))
                        .andExpect(status().isOk())
                        .andExpect(
                                header().string(
                                                "Content-Type",
                                                Matchers.equalToIgnoringCase(
                                                        "text/csv;charset=UTF-8")))
                        .andExpect(
                                header().string(
                                                "Content-Disposition",
                                                Matchers.containsString(".csv")))
                        .andReturn();
        byte[] bytes = result.getResponse().getContentAsByteArray();
        String csv = new String(bytes, StandardCharsets.UTF_8);

        assertThat(bytes).startsWith(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        assertThat(csv).startsWith("﻿# record\r\n");
        assertThat(csv)
                .contains("# trades\r\n")
                .contains("# fills\r\n")
                .contains("# exclusions\r\n");
        assertThat(csv).contains("dailyPriceRaw,SOURCE_REDISTRIBUTION_FORBIDDEN,");
        assertThat(csv.replace("\r\n", "")).doesNotContain("\n");
    }

    @Test
    @DisplayName("진행 중인 항목은 409 RESULT_NOT_READY 이고 공통 봉투를 쓴다")
    void 진행_중은_409() throws Exception {
        long owner = newUser("running");
        backtestService.create(request(owner, LocalDate.of(2024, 5, 9)));

        mockMvc.perform(export(recordIdOf(owner), "json").with(Requesters.as(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESULT_NOT_READY"));
    }

    @Test
    @DisplayName("실패한 항목은 설정값·참조 버전·사유·exclusions 만 담는다(FR-015)")
    void 실패는_설정과_사유만() throws Exception {
        long owner = newUser("failed");
        long runId = runIdOf(backtestService.create(request(owner, LocalDate.of(2024, 5, 10))));
        jdbc.update(
                "UPDATE backtest_run SET status = 'FAILED', failure_code = 'ENGINE_ERROR',"
                        + " failure_message = '엔진 오류' WHERE id = ?",
                runId);

        JsonNode json = exportJson(owner, recordIdOf(owner));

        assertThat(fieldNames(json))
                .containsExactly("record", "config", "referenceVersions", "failure", "exclusions");
        assertThat(json.at("/failure/code").asText()).isEqualTo("ENGINE_ERROR");
        assertThat(json.at("/record/status").asText()).isEqualTo("FAILED");
        assertThat(json.get("exclusions")).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("format 이 없거나 다른 값이면 400 이고, 남의 기록은 404 다")
    void 형식_오류와_남의_기록() throws Exception {
        long owner = newUser("bad");
        long other = newUser("other");
        completedRun(owner, LocalDate.of(2024, 5, 13), false);
        String recordId = recordIdOf(owner);

        mockMvc.perform(get("/my-records/{id}/export", recordId).with(Requesters.as(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mockMvc.perform(export(recordId, "xlsx").with(Requesters.as(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mockMvc.perform(export(recordId, "json").with(Requesters.as(other)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(export(recordId, "json")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("탐색 묶음은 searchSpace·candidates 를 담고 분석 전용 구획은 없다")
    void 탐색_묶음() throws Exception {
        long owner = newUser("bundle");
        ParamSearchResult accepted = paramSearchService.create(bundleCommand(owner));
        jdbc.update(
                "UPDATE param_search SET status = 'COMPLETED' WHERE search_id = ?",
                accepted.searchId());

        JsonNode json = exportJson(owner, recordIdOf(owner));

        assertThat(fieldNames(json))
                .containsExactly(
                        "record",
                        "config",
                        "referenceVersions",
                        "summary",
                        "searchSpace",
                        "candidates",
                        "exclusions");
        assertThat(json.at("/record/kind").asText()).isEqualTo("SEARCH_BUNDLE");
        assertThat(json.at("/config/instrumentIds")).hasSize(2);
        assertThat(json.at("/searchSpace/strategies").isObject()).isTrue();
        assertThat(json.at("/searchSpace/selectionRule").isObject()).isTrue();
        assertThat(json.at("/summary/counts/expected").asLong()).isPositive();
        assertThat(json.get("candidates").isArray()).isTrue();
        assertThat(json.get("candidates").size())
                .isEqualTo(json.at("/summary/counts/expected").asInt());
        assertThat(json.at("/candidates/0/status").asText()).isNotBlank();
    }

    @Test
    @DisplayName("경제환경 기록은 검색 설정을 config 에, 스냅샷 내용을 regime 에 담고 PK 와 연결 정보는 없다")
    void 경제환경() throws Exception {
        long owner = newUser("regime");
        jdbc.update("DELETE FROM regime_search_snapshot WHERE snapshot_key = ?", REGIME_KEY);
        insertSnapshot(REGIME_KEY, "EXPORT", "2026-06-01");
        registrar.recordRegimeSearch(owner, REGIME_KEY, EntryPath.MARKET_FLOW, BaselineOrigin.T_G);

        JsonNode json = exportJson(owner, recordIdOf(owner));

        assertThat(fieldNames(json))
                .containsExactly("record", "config", "referenceVersions", "regime", "exclusions");
        assertThat(json.at("/record/kind").asText()).isEqualTo("REGIME_SEARCH");
        assertThat(json.at("/config/mode").asText()).isEqualTo("FIELD");
        assertThat(json.at("/config/fieldId").asText()).isEqualTo("EXPORT");
        assertThat(json.at("/config/entryPath").asText()).isEqualTo("MARKET_FLOW");
        assertThat(json.at("/config/baselineOrigin").asText()).isEqualTo("T_G");
        assertThat(json.at("/referenceVersions/modelVersion").asText()).isEqualTo("REGIME-V1");
        assertThat(json.at("/regime/status").asText()).isEqualTo("NOT_COMPUTABLE");
        assertThat(json.at("/regime/candidateCounts").isObject()).isTrue();
        assertThat(json.at("/regime/id").isMissingNode()).isTrue();
        assertThat(json.at("/regime/link").isMissingNode()).isTrue();
    }

    // -- 준비 -------------------------------------------------------------------------

    /** 완료한 실행 하나. 결과 행·시계열 한 점·거래 한 건·예외 한 건을 넣고, {@code fills} 는 기록 여부에 따라 넣는다. */
    private long completedRun(long owner, LocalDate start, boolean fillsRecorded) {
        long runId = runIdOf(backtestService.create(request(owner, start)));
        jdbc.update("UPDATE backtest_run SET status = 'COMPLETED' WHERE id = ?", runId);
        jdbc.update(
                "INSERT INTO backtest_result (run_id, execution_id, finished_event_id, started_at,"
                        + " finished_at, portfolio_series, diagnostics, performance, execution,"
                        + " exceptions, fills_recorded, created_at, updated_at)"
                        + " VALUES (?, ?, ?, NOW(), NOW(), ?::jsonb, '{}'::jsonb, ?::jsonb,"
                        + " ?::jsonb, ?::jsonb, ?, NOW(), NOW())",
                runId,
                "EXEC-EXPORT-" + runId,
                "EVT-EXPORT-" + runId,
                """
                [{"date": "2024-05-07", "equity": "10000000.00", "cash": "10000000.00",
                  "positionValue": "0", "dailyReturn": "0", "cumulativeReturn": "0",
                  "drawdown": "0", "positions": []}]
                """,
                """
                {"initialEquity": "10000000.00", "finalEquity": "11200000.00", "currency": "KRW",
                 "totalReturn": "0.1250", "netTotalReturn": "0.1200", "cagr": "0.1000",
                 "maxDrawdown": "-0.0800", "sharpeRatio": "1.1", "volatility": "0.12",
                 "tradeCount": 1, "winRate": "1.0", "totalTradingCost": "0.0050"}
                """,
                """
                {"orderCount": 1, "fillCount": 1, "rejectedOrderCount": 0,
                 "totalCommission": "450", "totalSlippageCost": "300"}
                """,
                """
                [{"date": "2024-05-07", "instrumentId": "091160.XKRX", "kind": "ZERO_VOLATILITY",
                  "rule": "fallback-equal-weight", "action": "동일 비중으로 대체"}]
                """,
                fillsRecorded);
        jdbc.update(
                "INSERT INTO backtest_trade (id, run_id, trade_id, instrument_id, side, status,"
                        + " entry_time, exit_time, detail)"
                        + " VALUES (nextval('backtest_trade_seq'), ?, ?, '091160.XKRX', 'LONG',"
                        + " 'CLOSED', TIMESTAMPTZ '2024-05-07 09:00:00+09',"
                        + " TIMESTAMPTZ '2024-06-03 09:00:00+09', ?::jsonb)",
                runId,
                "T-" + runId,
                """
                {"tradeId": "T-%d", "instrumentId": "091160.XKRX", "side": "LONG",
                 "status": "CLOSED", "entryTime": "2024-05-07T00:00:00Z",
                 "exitTime": "2024-06-03T00:00:00Z", "entryPrice": "30000", "exitPrice": "31000",
                 "quantity": "100", "grossPnl": "100000", "commission": "450",
                 "slippageCost": "300", "realizedPnl": "99250", "returnRate": "0.0331",
                 "reason": "REBALANCE", "plannedDate": "2024-05-07"}
                """
                        .formatted(runId));
        if (fillsRecorded) {
            jdbc.update(
                    "INSERT INTO backtest_fill (id, run_id, fill_id, instrument_id, side, fill_time,"
                            + " detail)"
                            + " VALUES (nextval('backtest_fill_seq'), ?, ?, '091160.XKRX', 'BUY',"
                            + " TIMESTAMPTZ '2024-05-07 09:00:00+09', ?::jsonb)",
                    runId,
                    "F-" + runId,
                    """
                    {"fillId": "F-%d", "instrumentId": "091160.XKRX", "side": "BUY",
                     "time": "2024-05-07T00:00:00Z", "price": "30000", "referencePrice": "29990",
                     "quantity": "100", "costs": {"commission": "450", "spread": "0",
                     "slippage": "300", "tax": "0"}, "cashCost": "3000750",
                     "reason": "REBALANCE", "plannedDate": "2024-05-07"}
                    """
                            .formatted(runId));
        }
        return runId;
    }

    /** 배치가 쓰는 것과 같은 모양의 스냅샷 행. 사례는 없어도 되고 이 시험은 계산 결과를 보지 않는다({@code RegimeRecordIT}와 같다). */
    private void insertSnapshot(String key, String fieldId, String month) {
        jdbc.update(
                """
                INSERT INTO regime_search_snapshot (
                    snapshot_key, mode, field_id, baseline_month, revision, content_hash,
                    data_version, registry_version, model_version, status, status_reason,
                    scale, comparison_months, ranked_candidates, candidate_counts, data_asof,
                    computed_at, created_at, updated_at)
                VALUES (?, 'FIELD', ?, ?::date, 1, ?, 'FIXTURE-20260915', 'FIXTURE-REG-1',
                    'REGIME-V1', 'NOT_COMPUTABLE', 'NO_CANDIDATE', '{}'::jsonb, '{}'::jsonb,
                    '[]'::jsonb, ?::jsonb, ?::jsonb, now(), now(), now())
                """,
                key,
                fieldId,
                month,
                "e".repeat(64),
                """
                {"evaluated": 0, "excluded": {}}
                """,
                """
                {"indicators": {}, "newerReleases": []}
                """);
    }

    private JsonNode exportJson(long owner, String recordId) throws Exception {
        MvcResult result =
                mockMvc.perform(export(recordId, "json").with(Requesters.as(owner)))
                        .andExpect(status().isOk())
                        .andReturn();
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            export(String recordId, String format) {
        return get("/my-records/{id}/export", recordId).param("format", format);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private String recordIdOf(long owner) {
        return jdbc.queryForObject(
                "SELECT record_id FROM my_record WHERE owner_id = ? AND deleted_at IS NULL",
                String.class,
                owner);
    }

    private long runIdOf(BacktestJobResult result) {
        return jdbc.queryForObject(
                "SELECT run_id FROM backtest_request WHERE backtest_id = ?",
                Long.class,
                result.backtestId());
    }

    /** 후보 발행 대신 실행 행만 만든다. 후보·기준이 backtest_run(id)을 FK로 참조한다. */
    private long insertStubRun() {
        long id = nextRunId.getAndIncrement();
        jdbc.update(
                "INSERT INTO backtest_run (id, backtest_id, strategy_id, strategy_version,"
                        + " parameters, instrument_ids, start_date, end_date, initial_cash,"
                        + " position_sizing_type, random_seed, status, created_at, updated_at)"
                        + " VALUES (?, ?, 'STUB', '1', '{}'::jsonb, '[\"091160.XKRX\"]'::jsonb,"
                        + " DATE '2020-01-02', DATE '2026-08-31', 10000000.00,"
                        + " 'STRATEGY_DEFINED', 42, 'QUEUED', NOW(), NOW())",
                id,
                "BT-EXPORT-STUB-" + id);
        return id;
    }

    private long newUser(String tag) {
        String suffix = tag + (System.nanoTime() % 1_000_000_000L);
        String email = "my-record-export-" + suffix + "@t.test";
        jdbc.update(
                "INSERT INTO users (email, password_hash, nickname, phone_number, status,"
                        + " paper_ranking_enabled, live_ranking_enabled, ranking_display_nickname,"
                        + " created_at, updated_at)"
                        + " VALUES (?, 'x', ?, ?, 'ACTIVE', false, false, false, NOW(), NOW())",
                email,
                "e" + suffix,
                suffix);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private CreateParamSearchCommand bundleCommand(long requesterId) {
        return new CreateParamSearchCommand(
                requesterId,
                UNIVERSE,
                SearchableStrategies.of(registry, jdbc),
                null,
                null,
                null,
                Map.of(),
                registry.version());
    }

    private static CreateBacktestCommand request(Long requesterId, LocalDate start) {
        return new CreateBacktestCommand(
                requesterId,
                ExecutionType.SINGLE,
                "SECTOR-ETF-2026-09",
                List.of(
                        new CreateBacktestCommand.AllocationItem(
                                "SEMICONDUCTOR", "091160.XKRX", new BigDecimal("0.4000")),
                        new CreateBacktestCommand.AllocationItem(
                                "BATTERY", "305720.XKRX", new BigDecimal("0.6000"))),
                "FIXED_WEIGHT",
                "1",
                null,
                Map.of("rebalance", "MONTH_END"),
                start,
                LocalDate.of(2024, 11, 22),
                new BigDecimal("10000000.00"),
                null,
                "SPLIT-1",
                null,
                Scenario.FULL_PERIOD,
                null);
    }
}
