package com.ssafy.thorp.regimelink.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.backtest.application.port.MarketMetadataPort;
import com.ssafy.thorp.macro.support.MacroRegistrySeed;
import com.ssafy.thorp.support.IntegrationTestSupport;
import com.ssafy.thorp.support.MarketCatalogSeed;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 가용성 판정 조회 API 통합 시험 (T050·T052, contracts §1·§1a).
 *
 * <p>시장 자료는 공용 씨앗({@link MarketCatalogSeed})을 쓴다 — 자료 표에는 불변 트리거가 걸려 있어(001 FR-017) 검사가 자기 자료를 넣고
 * 지울 수 없다. 달력은 2014-01-01~2026-12-31 의 평일이고 반도체 ETF 의 상장일은 2015-01-02 다.
 *
 * <p>단위 시험이 판정 규칙을 보는 데 비해 여기서는 <b>응답의 모양</b>과 경로별 차이, 계약 예시와의 대조를 본다.
 */
@DisplayName("사례 연결 가용성 조회 API")
class AvailabilityControllerIT extends IntegrationTestSupport {

    private static final String SNAPSHOT_KEY =
            "FIELD:LABOR:2026-07-01:" + MarketCatalogSeed.DATA_VERSION + ":RL-REG-1:RL-MODEL-1";
    private static final String EMPTY_KEY =
            "FIELD:PRODUCTION:2026-07-01:"
                    + MarketCatalogSeed.DATA_VERSION
                    + ":RL-REG-1:RL-MODEL-1";
    private static final String INSTRUMENT = "091160.XKRX";
    private static final String SECTOR = "SEMICONDUCTOR";
    private static final String BASE_TOKEN = "9f2c1a7b4e0d83526af1c9d0b7e34512";
    private static final String COMPARE_TOKEN = "b41e7d0925c6438af10e5b2c96d47831";

    private static final long BASE_RUN = 900_001L;
    private static final long COMPARE_RUN = 900_002L;
    private static final long BASE_REQUEST = 900_101L;
    private static final long COMPARE_REQUEST = 900_102L;
    private static final long SNAPSHOT_ID_SEED = 900_201L;

    private final ObjectMapper json = new ObjectMapper();

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    /** 한 요청이 자료 버전을 몇 번 읽는지 센다(T110). 판정 동작은 그대로다. */
    @MockitoSpyBean private MarketMetadataPort marketMetadata;

    @BeforeEach
    void seed() {
        clean();
        // 010 은 008 등록표에서 지표 이름을 읽는다(T070). clean() 뒤라야 남는다.
        MacroRegistrySeed.seedRegistry(jdbc);
        seedRuns();
        seedSnapshot(SNAPSHOT_ID_SEED, SNAPSHOT_KEY, "NEAREST_ONLY", displayedMonths());
        seedSnapshot(SNAPSHOT_ID_SEED + 1, EMPTY_KEY, "NOT_COMPUTABLE", List.of());
    }

    @AfterEach
    void clean() {
        MacroRegistrySeed.clear(jdbc);
        // 이 검사가 만든 것만 지운다. 공용 DB 라 조건 없이 비우면 같은 표를 쓰는 다른 검사의 행까지
        // 사라지고, 그 실패는 실행 순서에 따라만 드러난다.
        jdbc.update(
                "DELETE FROM regime_link_use WHERE link_id IN"
                        + " (SELECT id FROM regime_link WHERE snapshot_key IN (?, ?))",
                SNAPSHOT_KEY,
                EMPTY_KEY);
        jdbc.update(
                "DELETE FROM regime_link_result WHERE link_id IN"
                        + " (SELECT id FROM regime_link WHERE snapshot_key IN (?, ?))",
                SNAPSHOT_KEY,
                EMPTY_KEY);
        jdbc.update(
                "DELETE FROM regime_link WHERE snapshot_key IN (?, ?)", SNAPSHOT_KEY, EMPTY_KEY);
        jdbc.update(
                "DELETE FROM regime_link_search WHERE snapshot_key IN (?, ?)",
                SNAPSHOT_KEY,
                EMPTY_KEY);
        jdbc.update(
                "DELETE FROM regime_link_filter WHERE definition ->> 'dataVersion' = ?",
                MarketCatalogSeed.DATA_VERSION);
        jdbc.update("DELETE FROM regime_search_case WHERE snapshot_id >= ?", SNAPSHOT_ID_SEED);
        jdbc.update("DELETE FROM regime_search_snapshot WHERE id >= ?", SNAPSHOT_ID_SEED);
        jdbc.update(
                "DELETE FROM backtest_request WHERE id IN (?, ?)", BASE_REQUEST, COMPARE_REQUEST);
        jdbc.update("DELETE FROM backtest_run WHERE id IN (?, ?)", BASE_RUN, COMPARE_RUN);
    }

    @Test
    @DisplayName("표시 후보 열이 순위 그대로 오고 자료 부족 사례만 제외 수에 든다")
    void 표시_후보를_순위_그대로_돌려준다() throws Exception {
        MvcResult response =
                mockMvc.perform(
                                get("/regime-links/availability")
                                        .param("base", BASE_TOKEN)
                                        .param("compare", COMPARE_TOKEN)
                                        .param("snapshotKey", SNAPSHOT_KEY)
                                        .param("entryPath", "STRATEGY_RESULT"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.success").value(true))
                        .andExpect(jsonPath("$.data.basis.kind").value("HISTORICAL_REVISION"))
                        .andExpect(jsonPath("$.data.search.snapshotKey").value(SNAPSHOT_KEY))
                        .andExpect(jsonPath("$.data.search.mode").value("field"))
                        .andExpect(jsonPath("$.data.search.status").value("NEAREST_ONLY"))
                        .andExpect(jsonPath("$.data.cases.length()").value(10))
                        .andExpect(jsonPath("$.data.filter.applied").value(true))
                        .andExpect(jsonPath("$.data.filter.minPostTradingDays").value(21))
                        .andExpect(jsonPath("$.data.filter.excludedCount").value(1))
                        .andReturn();

        JsonNode data = json.readTree(response.getResponse().getContentAsString()).get("data");
        List<Integer> ranks = new ArrayList<>();
        List<String> months = new ArrayList<>();
        for (JsonNode found : data.get("cases")) {
            ranks.add(found.get("rank").asInt());
            months.add(found.get("endMonth").asText());
        }
        assertThat(ranks).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        // 010 이 준 순서 그대로여야 하고, 빠진 자리를 아래 순위로 채우지 않는다.
        assertThat(months)
                .containsExactlyElementsOf(
                        displayedMonths().stream().map(YearMonth::toString).toList());

        JsonNode unlisted = data.get("cases").get(9);
        assertThat(unlisted.get("availability").get("state").asText())
                .isEqualTo("DATA_INSUFFICIENT");
        assertThat(unlisted.get("availability").get("reason").asText()).isEqualTo("UNLISTED");
        assertThat(unlisted.get("shownInFiltered").asBoolean()).isFalse();
        // 실행 둘의 판정이 실행별로 함께 온다.
        assertThat(unlisted.get("perRun")).hasSize(2);
        assertThat(data.get("cases").get(0).get("shownInFiltered").asBoolean()).isTrue();
        assertThat(data.get("cases").get(0).get("postWindow").get("completedTradingDays").asInt())
                .isEqualTo(63);
    }

    @Test
    @DisplayName("한 요청이 자료 버전을 읽는 수는 달력 질의 수와 무관하다 (T110)")
    void 자료_버전을_요청마다_한_번만_읽는다() throws Exception {
        clearInvocations(marketMetadata);
        mockMvc.perform(availabilityRequest()).andExpect(status().isOk());
        // 필터 키가 한 번, 달력은 요청 안의 트랜잭션마다 한 번이다. 달력 질의가 각자 읽던 때는 이 조건에서 96번이었다
        // (2026-09-26 측정). 사례·실행이 늘어도 이 수는 늘지 않는다.
        verify(marketMetadata, atMost(4)).currentDataVersion();
    }

    @Test
    @DisplayName("가용성 조회 30회 순차 응답 시간을 잰다 (T110, plan R9 p95 300ms 대조용 — 통과 조건이 아니다)")
    void 가용성_조회_응답_시간() throws Exception {
        mockMvc.perform(availabilityRequest()).andExpect(status().isOk()); // 준비 호출
        List<Long> millis = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            long started = System.nanoTime();
            mockMvc.perform(availabilityRequest()).andExpect(status().isOk());
            millis.add((System.nanoTime() - started) / 1_000_000);
        }
        List<Long> sorted = millis.stream().sorted().toList();
        long p95 = sorted.get((int) Math.ceil(0.95 * sorted.size()) - 1);
        System.out.printf(
                "regime-link availability p95=%dms max=%dms (n=30)%n", p95, sorted.get(29));
        assertThat(p95).isPositive();
    }

    private org.springframework.test.web.servlet.RequestBuilder availabilityRequest() {
        return get("/regime-links/availability")
                .param("base", BASE_TOKEN)
                .param("compare", COMPARE_TOKEN)
                .param("snapshotKey", SNAPSHOT_KEY)
                .param("entryPath", "STRATEGY_RESULT");
    }

    @Test
    @DisplayName("응답에 유지율과 초과수익이 없다")
    void 내보내지_않는_값은_응답에_없다() throws Exception {
        String body =
                mockMvc.perform(
                                get("/regime-links/availability")
                                        .param("base", BASE_TOKEN)
                                        .param("snapshotKey", SNAPSHOT_KEY)
                                        .param("entryPath", "STRATEGY_RESULT"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body).doesNotContain("retention").doesNotContain("excessReturn");
    }

    @Test
    @DisplayName("시장 흐름 경로는 필터를 걸지 않고 모든 사례를 기본 목록에 남긴다")
    void 시장_흐름_경로는_필터가_없다() throws Exception {
        mockMvc.perform(
                        get("/regime-links/availability")
                                .param("base", BASE_TOKEN)
                                .param("snapshotKey", SNAPSHOT_KEY)
                                .param("entryPath", "MARKET_FLOW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.filter.applied").value(false))
                .andExpect(jsonPath("$.data.filter.filterKey").doesNotExist())
                .andExpect(jsonPath("$.data.filter.excludedCount").doesNotExist())
                .andExpect(jsonPath("$.data.filter.availableRange").doesNotExist())
                .andExpect(jsonPath("$.data.filter.minPostTradingDays").value(21))
                .andExpect(jsonPath("$.data.cases[9].shownInFiltered").value(true))
                // 필터를 걸지 않아도 판정은 그대로 붙어 결과 자리에 사유가 보인다.
                .andExpect(
                        jsonPath("$.data.cases[9].availability.state").value("DATA_INSUFFICIENT"));
    }

    @Test
    @DisplayName("계산할 수 없는 검색은 후보 0건으로 200이다")
    void 계산_불가도_200이다() throws Exception {
        mockMvc.perform(
                        get("/regime-links/availability")
                                .param("base", BASE_TOKEN)
                                .param("snapshotKey", EMPTY_KEY)
                                .param("entryPath", "STRATEGY_RESULT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.search.status").value("NOT_COMPUTABLE"))
                .andExpect(jsonPath("$.data.cases.length()").value(0))
                .andExpect(jsonPath("$.data.filter.applied").value(true))
                .andExpect(jsonPath("$.data.filter.excludedCount").value(0));
    }

    @Test
    @DisplayName("필터를 건 응답에는 가용 범위가 늘 있다")
    void 필터를_걸면_가용_범위를_싣는다() throws Exception {
        mockMvc.perform(
                        get("/regime-links/availability")
                                .param("base", BASE_TOKEN)
                                .param("snapshotKey", SNAPSHOT_KEY)
                                .param("entryPath", "STRATEGY_RESULT"))
                .andExpect(status().isOk())
                // 덮는 사례가 없어도 키를 지우지 않는다 — 지우면 MARKET_FLOW 응답과 모양이 같아진다.
                .andExpect(jsonPath("$.data.filter.availableRange").exists());
    }

    @Test
    @DisplayName("같은 비교 묶음을 다시 물으면 같은 필터 키가 오고 행은 하나다")
    void 같은_묶음은_같은_필터_키다() throws Exception {
        String first = filterKey();
        String second = filterKey();

        assertThat(first).isEqualTo(second);
        assertThat(first).matches("[0-9a-f]{64}");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM regime_link_filter", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("사례 구간 조회는 실행 토큰 없이 공개로 동작한다")
    void 사례_구간_조회는_공개다() throws Exception {
        String caseId = SNAPSHOT_KEY + "~" + displayedMonths().get(0);

        mockMvc.perform(get("/regime-links/case-window").param("caseId", caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.basis.kind").value("HISTORICAL_REVISION"))
                .andExpect(jsonPath("$.data.caseId").value(caseId))
                .andExpect(jsonPath("$.data.postWindow.d0").isNotEmpty())
                .andExpect(jsonPath("$.data.postWindow.completedTradingDays").value(63))
                .andExpect(jsonPath("$.data.postWindow.complete63").value(true));
    }

    @Test
    @DisplayName("사례 식별자 형식이 틀리면 400, 없는 사례면 404다")
    void 사례_식별자를_검증한다() throws Exception {
        mockMvc.perform(get("/regime-links/case-window").param("caseId", "no-delimiter"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/regime-links/case-window").param("caseId", SNAPSHOT_KEY + "~1999-01"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("응답이 계약 예시와 같은 열쇠를 쓴다")
    void 계약_예시와_모양이_같다() throws Exception {
        JsonNode example =
                json.readTree(
                        Files.readString(
                                Path.of(
                                        "..",
                                        "docs",
                                        "specs",
                                        "regime-link-api",
                                        "examples",
                                        "availability-strategy-result.json")));
        String body =
                mockMvc.perform(
                                get("/regime-links/availability")
                                        .param("base", BASE_TOKEN)
                                        .param("compare", COMPARE_TOKEN)
                                        .param("snapshotKey", SNAPSHOT_KEY)
                                        .param("entryPath", "STRATEGY_RESULT"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        JsonNode actualData = json.readTree(body).get("data");
        JsonNode exampleData = example.get("data");
        assertThat(fieldNames(actualData)).isEqualTo(fieldNames(exampleData));
        assertThat(fieldNames(actualData.get("filter")))
                .isEqualTo(fieldNames(exampleData.get("filter")));
        assertThat(fieldNames(actualData.get("bundle").get("runs").get(0)))
                .isEqualTo(fieldNames(exampleData.get("bundle").get("runs").get(0)));
        assertThat(fieldNames(actualData.get("cases").get(0)))
                .isEqualTo(fieldNames(exampleData.get("cases").get(0)));
        assertThat(fieldNames(actualData.get("cases").get(0).get("postWindow")))
                .isEqualTo(fieldNames(exampleData.get("cases").get(0).get("postWindow")));
    }

    private String filterKey() throws Exception {
        String body =
                mockMvc.perform(
                                get("/regime-links/availability")
                                        .param("base", BASE_TOKEN)
                                        .param("compare", COMPARE_TOKEN)
                                        .param("snapshotKey", SNAPSHOT_KEY)
                                        .param("entryPath", "STRATEGY_RESULT"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return json.readTree(body).get("data").get("filter").get("filterKey").asText();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /**
     * 표시 후보 열.
     *
     * <p>앞의 아홉은 사후 63거래일을 채우고 마지막 하나는 <b>ETF 상장 전</b>이라 자료 부족이다 — 순위 보존과 제외 수, 아래 순위로 채우지 않는다는 성질을
     * 한 응답에서 함께 본다.
     */
    private static List<YearMonth> displayedMonths() {
        List<YearMonth> months = new ArrayList<>();
        YearMonth cursor = YearMonth.of(2025, 1);
        for (int i = 0; i < 9; i++) {
            months.add(cursor);
            cursor = cursor.plusMonths(1);
        }
        months.add(YearMonth.of(2014, 6));
        return months;
    }

    private void seedRuns() {
        insertRun(BASE_RUN, "BT-20260101-900001");
        insertRun(COMPARE_RUN, "BT-20260101-900002");
        insertRequest(BASE_REQUEST, BASE_RUN, "BT-20260101-900101", BASE_TOKEN);
        insertRequest(COMPARE_REQUEST, COMPARE_RUN, "BT-20260101-900102", COMPARE_TOKEN);
    }

    private void insertRun(long id, String backtestId) {
        jdbc.update(
                "INSERT INTO backtest_run (id, backtest_id, strategy_id, strategy_version,"
                        + " parameters, instrument_ids, start_date, end_date, initial_cash,"
                        + " position_sizing_type, random_seed, status, request_spec,"
                        + " effective_start, data_version, cost_profile_version, created_at,"
                        + " updated_at)"
                        + " VALUES (?, ?, 'FIXED_WEIGHT', '1.0.0', '{}'::jsonb, ?::jsonb,"
                        + " DATE '2015-01-02', DATE '2026-12-31', 10000000.00,"
                        + " 'STRATEGY_DEFINED', 42, 'COMPLETED', ?::jsonb,"
                        // 비용 버전은 응답 §1 의 bundle.runs[] 에 실린다(T061) — 비우면 NON_NULL 에
                        // 걸려 키가 빠지고 계약 예시와 열쇠가 어긋난다.
                        + " DATE '2015-04-01', ?, 'PROVISIONAL-DEMO-1', NOW(), NOW())",
                id,
                backtestId,
                "[\"" + INSTRUMENT + "\"]",
                // 워밍업은 실행 계획의 request_spec 에 남는다 — 별도 컬럼이 없다.
                "{\"mappingVersion\":\""
                        + MarketCatalogSeed.MAP_VERSION
                        + "\",\"warmupTradingDays\":63,\"items\":[{\"sectorId\":\""
                        + SECTOR
                        + "\",\"instrumentId\":\""
                        + INSTRUMENT
                        + "\"}]}",
                MarketCatalogSeed.DATA_VERSION);
    }

    private void insertRequest(long id, long runId, String backtestId, String token) {
        jdbc.update(
                "INSERT INTO backtest_request (id, backtest_id, run_id, requester_id,"
                        + " execution_type, result_source, public_token, public_token_expires_at,"
                        + " created_at, updated_at)"
                        + " VALUES (?, ?, ?, NULL, 'SINGLE', 'NEW', ?, NOW() + INTERVAL '30 days',"
                        + " NOW(), NOW())",
                id,
                backtestId,
                runId,
                token);
    }

    private void seedSnapshot(long id, String key, String status, List<YearMonth> months) {
        jdbc.update(
                "INSERT INTO regime_search_snapshot (id, snapshot_key, mode, field_id,"
                        + " baseline_month, revision, content_hash, data_version, registry_version,"
                        + " model_version, status, status_reason, scale, comparison_months,"
                        + " ranked_candidates, candidate_counts, data_asof, computed_at,"
                        + " created_at, updated_at)"
                        + " VALUES (?, ?, 'FIELD', ?, DATE '2026-07-01', 1, ?, ?, 'RL-REG-1',"
                        + " 'RL-MODEL-1', ?, ?, '{}'::jsonb, '{}'::jsonb, '[]'::jsonb, '{}'::jsonb,"
                        + " '{}'::jsonb, NOW(), NOW(), NOW())",
                id,
                key,
                key.startsWith("FIELD:LABOR") ? "labor" : "production",
                String.valueOf(id).repeat(64).substring(0, 64),
                MarketCatalogSeed.DATA_VERSION,
                status,
                // 계산 불가는 사유가 반드시 있다(010 스키마 제약).
                "NOT_COMPUTABLE".equals(status) ? "NO_CANDIDATE" : "THRESHOLD_NOT_CALIBRATED");
        int rank = 1;
        for (YearMonth month : months) {
            jdbc.update(
                    "INSERT INTO regime_search_case (snapshot_id, rank, end_month, distance,"
                            + " indicator_values, explanation, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?::jsonb, '{}'::jsonb, '{}'::jsonb, NOW(), NOW())",
                    id,
                    rank,
                    month.atDay(1),
                    "{\"value\":\"0.4" + String.format("%04d", rank) + "\"}");
            rank++;
        }
    }
}
