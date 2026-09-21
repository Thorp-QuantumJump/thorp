package com.ssafy.thorp.regimelink.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.support.IntegrationTestSupport;
import com.ssafy.thorp.support.MarketCatalogSeed;
import com.ssafy.thorp.support.Requesters;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 사례 연결 생성·조회 API 의 DB 경로 (T036·T070·T076 가운데 HTTP 로 볼 수 있는 몫).
 *
 * <p>여기서 보는 것은 <b>행이 생기는가·중복되지 않는가·누가 열 수 있는가</b>다. 구간 계산은 워커 몫이라 이 시험에 없고, 계산 작업을 만들지 않는 판정(자료
 * 부족)으로 흐름 전체를 지난다 — 그 경로는 워커도 브로커도 필요 없다.
 *
 * <p>시장 자료는 공용 씨앗({@link MarketCatalogSeed})을 쓴다. 반도체 ETF 상장일이 2015-01-02 라 그 앞의 사례는 {@code
 * DATA_INSUFFICIENT(UNLISTED)}다.
 */
@DisplayName("사례 연결 생성·조회 API")
class RegimeLinkApiIT extends IntegrationTestSupport {

    private static final String SNAPSHOT_KEY =
            "FIELD:LABOR:2026-07-01:" + MarketCatalogSeed.DATA_VERSION + ":RLA-REG-1:RLA-MODEL-1";
    private static final String INSTRUMENT = "091160.XKRX";
    private static final String SECTOR = "SEMICONDUCTOR";
    private static final String BASE_TOKEN = "1a2b3c4d5e6f70819a2b3c4d5e6f7081";
    private static final String COMPARE_TOKEN = "2b3c4d5e6f70819a2b3c4d5e6f708192";
    private static final String OTHER_TOKEN = "3c4d5e6f70819a2b3c4d5e6f70819a2b";

    /** ETF 상장 전 사례 — 계산 작업을 만들지 않는 판정이라 브로커 없이 끝까지 지난다. */
    private static final YearMonth UNLISTED_CASE = YearMonth.of(2014, 6);

    private static final String UNLISTED_CASE_ID = SNAPSHOT_KEY + "~" + UNLISTED_CASE;

    private static final long BASE_RUN = 910_001L;
    private static final long COMPARE_RUN = 910_002L;
    private static final long OTHER_RUN = 910_003L;
    private static final long BASE_REQUEST = 910_101L;
    private static final long COMPARE_REQUEST = 910_102L;
    private static final long OTHER_REQUEST = 910_103L;
    private static final long SNAPSHOT_ID = 910_201L;

    private final ObjectMapper json = new ObjectMapper();

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    private long owner;

    @BeforeEach
    void seed() {
        clean();
        owner = newUser();
        insertRun(BASE_RUN, "BT-20260101-910001", SECTOR);
        insertRun(COMPARE_RUN, "BT-20260101-910002", SECTOR);
        // 조건이 어긋난 묶음(다른 섹터)을 만들어 두고 T093 거절을 본다.
        insertRun(OTHER_RUN, "BT-20260101-910003", "BIO");
        insertRequest(BASE_REQUEST, BASE_RUN, "BT-20260101-910101", BASE_TOKEN);
        insertRequest(COMPARE_REQUEST, COMPARE_RUN, "BT-20260101-910102", COMPARE_TOKEN);
        insertRequest(OTHER_REQUEST, OTHER_RUN, "BT-20260101-910103", OTHER_TOKEN);
        seedSnapshot();
    }

    @AfterEach
    void clean() {
        jdbc.update(
                "DELETE FROM regime_link_use WHERE link_id IN"
                        + " (SELECT id FROM regime_link WHERE snapshot_key = ?)",
                SNAPSHOT_KEY);
        jdbc.update("DELETE FROM regime_link WHERE snapshot_key = ?", SNAPSHOT_KEY);
        jdbc.update("DELETE FROM regime_link_search WHERE snapshot_key = ?", SNAPSHOT_KEY);
        jdbc.update("DELETE FROM regime_search_case WHERE snapshot_id = ?", SNAPSHOT_ID);
        jdbc.update("DELETE FROM regime_search_snapshot WHERE id = ?", SNAPSHOT_ID);
        jdbc.update(
                "DELETE FROM backtest_request WHERE id IN (?, ?, ?)",
                BASE_REQUEST,
                COMPARE_REQUEST,
                OTHER_REQUEST);
        jdbc.update(
                "DELETE FROM backtest_run WHERE id IN (?, ?, ?)", BASE_RUN, COMPARE_RUN, OTHER_RUN);
    }

    // --- 검색 기록 (contracts §1b) ------------------------------------------------

    @Test
    @DisplayName("익명 검색 기록 생성은 401 이고 행을 만들지 않는다")
    void 익명_검색_기록() throws Exception {
        mockMvc.perform(
                        post("/regime-link-searches")
                                .contentType(MediaType.APPLICATION_JSON)
                                .with(csrf())
                                .content(searchBody("anon-1", true)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));

        assertThat(searchRows()).isZero();
    }

    @Test
    @DisplayName("같은 멱등 키의 같은 본문 재시도는 200 이고 기록을 늘리지 않는다")
    void 검색_기록_멱등() throws Exception {
        String body = searchBody("idem-1", true);
        mockMvc.perform(
                        post("/regime-link-searches")
                                .contentType(MediaType.APPLICATION_JSON)
                                .with(csrf())
                                .content(body)
                                .with(Requesters.as(owner)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.linkSearchId").exists())
                .andExpect(jsonPath("$.data.basis.kind").value("HISTORICAL_REVISION"));

        mockMvc.perform(
                        post("/regime-link-searches")
                                .contentType(MediaType.APPLICATION_JSON)
                                .with(csrf())
                                .content(body)
                                .with(Requesters.as(owner)))
                .andExpect(status().isOk());

        assertThat(searchRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 멱등 키에 다른 본문이면 400 이고 기록 행 수가 그대로다")
    void 멱등_키_재사용_불일치() throws Exception {
        mockMvc.perform(
                        post("/regime-link-searches")
                                .contentType(MediaType.APPLICATION_JSON)
                                .with(csrf())
                                .content(searchBody("idem-2", true))
                                .with(Requesters.as(owner)))
                .andExpect(status().isCreated());

        mockMvc.perform(
                        post("/regime-link-searches")
                                .contentType(MediaType.APPLICATION_JSON)
                                .with(csrf())
                                .content(searchBody("idem-2", false))
                                .with(Requesters.as(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

        assertThat(searchRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("조건이 어긋난 비교 묶음은 400 이고 기록도 링크도 만들지 않는다")
    void 어긋난_묶음_거절() throws Exception {
        String body =
                """
                {"clientRequestId":"mismatch-1","snapshotKey":"%s","base":"%s","compare":"%s",
                 "entryPath":"STRATEGY_RESULT","fullSearchShown":true}
                """
                        .formatted(SNAPSHOT_KEY, BASE_TOKEN, OTHER_TOKEN);

        mockMvc.perform(
                        post("/regime-link-searches")
                                .contentType(MediaType.APPLICATION_JSON)
                                .with(csrf())
                                .content(body)
                                .with(Requesters.as(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

        assertThat(searchRows()).isZero();
        assertThat(linkRows()).isZero();
    }

    // --- 사례 열기 (contracts §2) --------------------------------------------------

    @Test
    @DisplayName("익명 사례 열기는 401 이고 링크도 이용 기록도 만들지 않는다")
    void 익명_사례_열기() throws Exception {
        String searchId = createSearch("open-anon");

        mockMvc.perform(
                        post("/regime-links")
                                .contentType(MediaType.APPLICATION_JSON)
                                .with(csrf())
                                .content(openBody(searchId, UNLISTED_CASE_ID)))
                .andExpect(status().isUnauthorized());

        assertThat(linkRows()).isZero();
        assertThat(useRows()).isZero();
    }

    @Test
    @DisplayName("자료 부족 사례는 결과 없는 COMPLETED 링크로 남고 다시 열어도 행이 늘지 않는다")
    void 자료_부족은_결과_없이_끝난다() throws Exception {
        String searchId = createSearch("open-1");

        mockMvc.perform(
                        post("/regime-links")
                                .contentType(MediaType.APPLICATION_JSON)
                                .with(csrf())
                                .content(openBody(searchId, UNLISTED_CASE_ID))
                                .with(Requesters.as(owner)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.links.length()").value(2))
                .andExpect(jsonPath("$.data.links[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.links[0].publicToken").value(BASE_TOKEN));

        assertThat(linkRows()).isEqualTo(2);
        assertThat(useRows()).isEqualTo(2);

        // 같은 (실행 × 사례 × 시나리오) 는 다시 만들지 않는다(FR-010).
        mockMvc.perform(
                        post("/regime-links")
                                .contentType(MediaType.APPLICATION_JSON)
                                .with(csrf())
                                .content(openBody(searchId, UNLISTED_CASE_ID))
                                .with(Requesters.as(owner)))
                .andExpect(status().isAccepted());

        assertThat(linkRows()).isEqualTo(2);
        assertThat(useRows()).isEqualTo(2);
    }

    @Test
    @DisplayName("다른 사용자의 검색 기록으로는 열 수 없다")
    void 남의_검색_기록() throws Exception {
        String searchId = createSearch("open-2");
        long other = newUser();

        mockMvc.perform(
                        post("/regime-links")
                                .contentType(MediaType.APPLICATION_JSON)
                                .with(csrf())
                                .content(openBody(searchId, UNLISTED_CASE_ID))
                                .with(Requesters.as(other)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        assertThat(linkRows()).isZero();
    }

    @Test
    @DisplayName("표시 후보 밖 사례는 404 다")
    void 표시_후보_밖() throws Exception {
        String searchId = createSearch("open-3");

        mockMvc.perform(
                        post("/regime-links")
                                .contentType(MediaType.APPLICATION_JSON)
                                .with(csrf())
                                .content(openBody(searchId, SNAPSHOT_KEY + "~1999-01"))
                                .with(Requesters.as(owner)))
                .andExpect(status().isNotFound());

        assertThat(linkRows()).isZero();
    }

    @Test
    @DisplayName("알 수 없는 시나리오는 400 이고 기본값으로 떨어뜨리지 않는다")
    void 알_수_없는_시나리오() throws Exception {
        String searchId = createSearch("open-4");
        String body =
                """
                {"linkSearchId":"%s","caseId":"%s","scenario":"WHATEVER","confirmFullPeriod":false}
                """
                        .formatted(searchId, UNLISTED_CASE_ID);

        mockMvc.perform(
                        post("/regime-links")
                                .contentType(MediaType.APPLICATION_JSON)
                                .with(csrf())
                                .content(body)
                                .with(Requesters.as(owner)))
                .andExpect(status().isBadRequest());

        assertThat(linkRows()).isZero();
    }

    // --- 링크 조회 (contracts §3) --------------------------------------------------

    @Test
    @DisplayName("링크 조회는 토큰이 필수이고 맞지 않는 토큰은 404 다")
    void 링크_조회_토큰() throws Exception {
        String searchId = createSearch("read-1");
        String linkId = openFirstLink(searchId);

        mockMvc.perform(get("/regime-links/{linkId}", linkId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

        mockMvc.perform(get("/regime-links/{linkId}", linkId).param("token", OTHER_TOKEN))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/regime-links/{linkId}", linkId).param("token", BASE_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.linkId").value(linkId))
                .andExpect(jsonPath("$.data.publicToken").value(BASE_TOKEN))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.availability.state").value("DATA_INSUFFICIENT"))
                .andExpect(jsonPath("$.data.availability.reason").value("UNLISTED"))
                // 계산 작업을 만든 적이 없으므로 큐 시각이 없다(contracts §3).
                .andExpect(jsonPath("$.data.queuedAt").doesNotExist())
                .andExpect(jsonPath("$.data.result").doesNotExist())
                .andExpect(jsonPath("$.data.failure").doesNotExist());
    }

    @Test
    @DisplayName("사례 묶음 조회는 기준 → 비교 대상 순서로 각자의 토큰을 싣는다")
    void 사례_묶음_조회() throws Exception {
        createSearchAndOpen("read-2");

        mockMvc.perform(
                        get("/regime-links")
                                .param("base", BASE_TOKEN)
                                .param("compare", COMPARE_TOKEN)
                                .param("caseId", UNLISTED_CASE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].publicToken").value(BASE_TOKEN))
                .andExpect(jsonPath("$.data[1].publicToken").value(COMPARE_TOKEN));
    }

    @Test
    @DisplayName("링크가 없는 조합은 요소가 없다 — 빈 값을 만들지 않는다")
    void 없는_조합() throws Exception {
        mockMvc.perform(
                        get("/regime-links")
                                .param("base", BASE_TOKEN)
                                .param("caseId", UNLISTED_CASE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // --- 재시도 (contracts §5) -----------------------------------------------------

    @Test
    @DisplayName("실패하지 않은 링크의 재시도는 409 다")
    void 재시도_불가() throws Exception {
        String searchId = createSearch("retry-1");
        String linkId = openFirstLink(searchId);

        mockMvc.perform(
                        post("/regime-links/{linkId}/retry", linkId)
                                .with(csrf())
                                .with(Requesters.as(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NOT_RETRYABLE"));
    }

    // --- 도우미 -------------------------------------------------------------------

    private String createSearch(String clientRequestId) throws Exception {
        String body =
                mockMvc.perform(
                                post("/regime-link-searches")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .with(csrf())
                                        .content(searchBody(clientRequestId, true))
                                        .with(Requesters.as(owner)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return json.readTree(body).get("data").get("linkSearchId").asText();
    }

    private void createSearchAndOpen(String clientRequestId) throws Exception {
        openFirstLink(createSearch(clientRequestId));
    }

    private String openFirstLink(String searchId) throws Exception {
        String body =
                mockMvc.perform(
                                post("/regime-links")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .with(csrf())
                                        .content(openBody(searchId, UNLISTED_CASE_ID))
                                        .with(Requesters.as(owner)))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return json.readTree(body).get("data").get("links").get(0).get("linkId").asText();
    }

    private static String searchBody(String clientRequestId, boolean fullSearchShown) {
        return """
               {"clientRequestId":"%s","snapshotKey":"%s","base":"%s","compare":"%s",
                "entryPath":"STRATEGY_RESULT","fullSearchShown":%s}
               """
                .formatted(
                        clientRequestId, SNAPSHOT_KEY, BASE_TOKEN, COMPARE_TOKEN, fullSearchShown);
    }

    private static String openBody(String searchId, String caseId) {
        return """
               {"linkSearchId":"%s","caseId":"%s","scenario":"CONTINUOUS",
                "confirmFullPeriod":false}
               """
                .formatted(searchId, caseId);
    }

    private int searchRows() {
        return count("SELECT count(*) FROM regime_link_search WHERE snapshot_key = ?");
    }

    private int linkRows() {
        return count("SELECT count(*) FROM regime_link WHERE snapshot_key = ?");
    }

    private int useRows() {
        return count(
                "SELECT count(*) FROM regime_link_use WHERE link_id IN"
                        + " (SELECT id FROM regime_link WHERE snapshot_key = ?)");
    }

    private int count(String sql) {
        Integer found = jdbc.queryForObject(sql, Integer.class, SNAPSHOT_KEY);
        return found == null ? 0 : found;
    }

    private long newUser() {
        String suffix = "rl" + (System.nanoTime() % 1_000_000_000L);
        String email = "regime-link-api-" + suffix + "@t.test";
        jdbc.update(
                "INSERT INTO users (email, password_hash, nickname, phone_number, status,"
                        + " paper_ranking_enabled, live_ranking_enabled, ranking_display_nickname,"
                        + " created_at, updated_at)"
                        + " VALUES (?, 'x', ?, ?, 'ACTIVE', false, false, false, NOW(), NOW())",
                email,
                "a" + suffix,
                suffix);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private void insertRun(long id, String backtestId, String sectorId) {
        jdbc.update(
                "INSERT INTO backtest_run (id, backtest_id, strategy_id, strategy_version,"
                        + " parameters, instrument_ids, start_date, end_date, initial_cash,"
                        + " position_sizing_type, random_seed, status, request_spec,"
                        + " effective_start, data_version, created_at, updated_at)"
                        + " VALUES (?, ?, 'FIXED_WEIGHT', '1.0.0', '{}'::jsonb, ?::jsonb,"
                        + " DATE '2015-01-02', DATE '2026-12-31', 10000000.00,"
                        + " 'STRATEGY_DEFINED', 42, 'COMPLETED', ?::jsonb,"
                        + " DATE '2015-04-01', ?, NOW(), NOW())",
                id,
                backtestId,
                "[\"" + INSTRUMENT + "\"]",
                "{\"mappingVersion\":\""
                        + MarketCatalogSeed.MAP_VERSION
                        + "\",\"warmupTradingDays\":63,\"items\":[{\"sectorId\":\""
                        + sectorId
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

    /** 표시 후보는 하나다 — 열고 조회하는 흐름만 보므로 목록의 길이는 이 시험의 관심이 아니다. */
    private void seedSnapshot() {
        jdbc.update(
                "INSERT INTO regime_search_snapshot (id, snapshot_key, mode, field_id,"
                        + " baseline_month, revision, content_hash, data_version, registry_version,"
                        + " model_version, status, status_reason, scale, comparison_months,"
                        + " ranked_candidates, candidate_counts, data_asof, computed_at,"
                        + " created_at, updated_at)"
                        + " VALUES (?, ?, 'FIELD', 'LABOR', DATE '2026-07-01', 1, ?, ?,"
                        + " 'RLA-REG-1', 'RLA-MODEL-1', 'NEAREST_ONLY',"
                        + " 'THRESHOLD_NOT_CALIBRATED', '{}'::jsonb, '{}'::jsonb, '[]'::jsonb,"
                        + " '{}'::jsonb, '{}'::jsonb, NOW(), NOW(), NOW())",
                SNAPSHOT_ID,
                SNAPSHOT_KEY,
                String.valueOf(SNAPSHOT_ID).repeat(64).substring(0, 64),
                MarketCatalogSeed.DATA_VERSION);
        List<YearMonth> months = new ArrayList<>(List.of(UNLISTED_CASE));
        int rank = 1;
        for (YearMonth month : months) {
            jdbc.update(
                    "INSERT INTO regime_search_case (snapshot_id, rank, end_month, distance,"
                            + " indicator_values, explanation, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?::jsonb, '{}'::jsonb, '{}'::jsonb, NOW(), NOW())",
                    SNAPSHOT_ID,
                    rank,
                    month.atDay(1),
                    "{\"value\":\"0.4" + String.format("%04d", rank) + "\"}");
            rank++;
        }
    }
}
