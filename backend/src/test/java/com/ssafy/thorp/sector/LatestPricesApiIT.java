package com.ssafy.thorp.sector;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /sectors/latest-prices} 계약 검증.
 *
 * <p>확인하는 것은 넷이다. 섹터마다 마지막 날과 그 직전 날의 수정종가가 오는지, 거래일이 하루뿐인 종목의 직전 값이 비어 오는지, 가격이 없는 섹터가 빠지지 않고
 * {@code missingSectorCodes}로 밝혀지는지, 부분 갱신된 자료 버전을 고르지 않는지다. 어느 섹터에도 가격이 없을 때의 409도 본다.
 *
 * <p>픽스처를 넣는 방식과 대응표를 검사마다 가르는 까닭은 {@link PriceSeriesApiIT}와 같다.
 *
 * <p>Docker가 없으면 통째로 skip된다({@code PostgresTestSupport}). 결과의 {@code skipped}를 확인한다.
 */
class LatestPricesApiIT extends IntegrationTestSupport {

    private static final String BOTH_MAP = "SEM-LP-BOTH";
    private static final String MISSING_MAP = "SEM-LP-MISSING";
    private static final String PARTIAL_MAP = "SEM-LP-PARTIAL";
    private static final String EMPTY_MAP = "SEM-LP-EMPTY";

    private static final String CONSTRUCTION = "CONSTRUCTION_LP";
    private static final String BANK = "BANK_LP";
    private static final String CONSTRUCTION_ETF = "117700.XKRX";
    private static final String BANK_ETF = "091170.XKRX";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        deleteFixtures();
    }

    @AfterEach
    void restore() {
        deleteFixtures();
    }

    @Test
    @DisplayName("섹터마다 마지막 거래일과 그 직전 거래일의 수정종가가 온다")
    void returnsLastTwoClosesPerSector() throws Exception {
        String datasetId = "KRX_DAILY_LP_BOTH";
        long mapVersionId = publishMap(BOTH_MAP);
        publishDataset(mapVersionId, datasetId, "2026-09-22 15:30:00+09", true);
        // 일부러 뒤섞어 넣는다. 마지막 날을 저장 순서로 고르고 있으면 여기서 드러난다.
        insertPrice(datasetId, CONSTRUCTION_ETF, "2026-09-18", "10500.0000", true, true);
        insertPrice(datasetId, CONSTRUCTION_ETF, "2026-09-16", "10000.0000", false, false);
        insertPrice(datasetId, CONSTRUCTION_ETF, "2026-09-17", "10250.5000", false, false);
        // 거래일이 하루뿐이다. 직전 값을 0이나 같은 값으로 채우면 "변화 없음"으로 읽힌다.
        insertPrice(datasetId, BANK_ETF, "2026-09-18", "8000.0000", false, false);

        String construction = "$.data.prices[?(@.sectorCode == '" + CONSTRUCTION + "')]";
        String bank = "$.data.prices[?(@.sectorCode == '" + BANK + "')]";
        mockMvc.perform(get("/sectors/latest-prices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mapVersion").value(BOTH_MAP))
                .andExpect(jsonPath("$.data.datasetId").value(datasetId))
                .andExpect(jsonPath("$.data.prices.length()").value(2))
                .andExpect(jsonPath("$.data.missingSectorCodes.length()").value(0))
                .andExpect(jsonPath(construction + ".instrumentId", contains(CONSTRUCTION_ETF)))
                .andExpect(jsonPath(construction + ".name", contains("KODEX 건설")))
                .andExpect(jsonPath(construction + ".date", contains("2026-09-18")))
                .andExpect(jsonPath(construction + ".adjustedClose", contains("10500.00000000")))
                .andExpect(jsonPath(construction + ".tradingSuspended", contains(true)))
                .andExpect(jsonPath(construction + ".priceCarriedForward", contains(true)))
                .andExpect(jsonPath(construction + ".previousDate", contains("2026-09-17")))
                .andExpect(
                        jsonPath(
                                construction + ".previousAdjustedClose",
                                contains("10250.50000000")))
                .andExpect(jsonPath(bank + ".date", contains("2026-09-18")))
                .andExpect(jsonPath(bank + ".previousDate", contains(nullValue())))
                .andExpect(jsonPath(bank + ".previousAdjustedClose", contains(nullValue())));
    }

    @Test
    @DisplayName("가격이 없는 섹터는 빼지 않고 missingSectorCodes로 밝힌다")
    void reportsSectorsWithoutPrices() throws Exception {
        String datasetId = "KRX_DAILY_LP_MISSING";
        long mapVersionId = publishMap(MISSING_MAP);
        publishDataset(mapVersionId, datasetId, "2026-09-22 15:30:00+09", true);
        insertPrice(datasetId, CONSTRUCTION_ETF, "2026-09-16", "10000.0000", false, false);

        mockMvc.perform(get("/sectors/latest-prices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.prices.length()").value(1))
                .andExpect(jsonPath("$.data.prices[0].sectorCode").value(CONSTRUCTION))
                .andExpect(jsonPath("$.data.missingSectorCodes", contains(BANK)));
    }

    @Test
    @DisplayName("부분 갱신된 자료 버전은 고르지 않는다 (FR-014)")
    void ignoresIncompleteDataset() throws Exception {
        String fullDatasetId = "KRX_DAILY_LP_FULL";
        String partialDatasetId = "KRX_DAILY_LP_PARTIAL";
        long mapVersionId = publishMap(PARTIAL_MAP);
        publishDataset(mapVersionId, fullDatasetId, "2026-09-22 15:30:00+09", true);
        publishDataset(mapVersionId, partialDatasetId, "2026-09-23 15:30:00+09", false);
        insertPrice(fullDatasetId, CONSTRUCTION_ETF, "2026-09-16", "10000.0000", false, false);
        insertPrice(partialDatasetId, CONSTRUCTION_ETF, "2026-09-17", "99999.0000", false, false);

        mockMvc.perform(get("/sectors/latest-prices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.datasetId").value(fullDatasetId))
                .andExpect(jsonPath("$.data.prices[0].date").value("2026-09-16"))
                .andExpect(jsonPath("$.data.prices[0].adjustedClose").value("10000.00000000"));
    }

    @Test
    @DisplayName("어느 섹터에도 가격이 없으면 빈 목록이 아니라 409다")
    void noPricesAtAllIsConflict() throws Exception {
        long mapVersionId = publishMap(EMPTY_MAP);
        publishDataset(mapVersionId, "KRX_DAILY_LP_EMPTY", "2026-09-22 15:30:00+09", true);

        mockMvc.perform(get("/sectors/latest-prices"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PRICE_SERIES_NOT_READY"));
    }

    /**
     * 픽스처를 FK 역순으로 지운다.
     *
     * <p>{@code dataset_version}에는 UPDATE·DELETE를 거부하는 불변 트리거가 걸려 있다(FR-017). 그래서 그 표는 지우지 못하고 이 검사
     * 전용 {@code dataset_id}를 써서 이미 있으면 다시 넣지 않는다. {@code daily_price}도 같은 트리거가 걸려 있어 지우지 않고, 대신 이
     * 검사만 쓰는 자료 버전에 넣어 다른 검사와 섞이지 않게 한다.
     *
     * <p>대응표는 반드시 비운다. {@code effective_to IS NULL}인 행을 남기면 부분 UNIQUE 인덱스 때문에 다음 검사의 INSERT가 실패한다.
     */
    private void deleteFixtures() {
        jdbcTemplate.update(
                "UPDATE sector_etf_map_version"
                        + " SET effective_to = effective_from + INTERVAL '1 second'"
                        + " WHERE effective_to IS NULL");
        jdbcTemplate.update(
                "DELETE FROM sector_etf_map_entry WHERE sector_id IN"
                        + " (SELECT id FROM sector WHERE code IN (?, ?))",
                CONSTRUCTION,
                BANK);
        jdbcTemplate.update("DELETE FROM sector WHERE code IN (?, ?)", CONSTRUCTION, BANK);
    }

    /** 그 이름의 대응표를 열고 섹터 둘을 붙인다. 반환값은 대응표 버전의 PK다. */
    private long publishMap(String mapVersion) {
        Long mapVersionId =
                jdbcTemplate.queryForObject(
                        """
                        INSERT INTO sector_etf_map_version
                            (version, effective_from, effective_to, is_sample, published_at)
                        VALUES (?, TIMESTAMPTZ '2026-09-23 00:00:00+09', NULL, TRUE,
                                TIMESTAMPTZ '2026-09-23 00:00:00+09')
                        ON CONFLICT (version) DO UPDATE SET effective_to = NULL
                        RETURNING id
                        """,
                        Long.class,
                        mapVersion);
        insertEntry(mapVersionId, insertSector(CONSTRUCTION, "건설"), etfId(CONSTRUCTION_ETF));
        insertEntry(mapVersionId, insertSector(BANK, "은행"), etfId(BANK_ETF));
        return mapVersionId;
    }

    private long insertSector(String code, String name) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO sector (code, name, classification) VALUES (?, ?, 'INDUSTRY')"
                        + " RETURNING id",
                Long.class,
                code,
                name);
    }

    /** 대표 ETF는 여러 검사가 공유하므로 있으면 그대로 쓴다. */
    private long etfId(String instrumentId) {
        boolean construction = CONSTRUCTION_ETF.equals(instrumentId);
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO representative_etf
                    (instrument_id, name, listed_on, benchmark_index, manager)
                VALUES (?, ?, DATE '2009-10-30', ?, '삼성자산운용')
                ON CONFLICT (instrument_id) DO UPDATE
                    SET name = EXCLUDED.name,
                        benchmark_index = EXCLUDED.benchmark_index
                RETURNING id
                """,
                Long.class,
                instrumentId,
                construction ? "KODEX 건설" : "KODEX 은행",
                construction ? "KRX 건설" : "KRX 은행");
    }

    private void insertEntry(long mapVersionId, long sectorId, long etfId) {
        jdbcTemplate.update(
                "INSERT INTO sector_etf_map_entry (map_version_id, sector_id, etf_id)"
                        + " VALUES (?, ?, ?)",
                mapVersionId,
                sectorId,
                etfId);
    }

    /**
     * 자료 버전을 만든다. 이미 있으면 그 행의 PK를 돌려준다.
     *
     * <p>불변 트리거가 UPDATE를 거부하므로 {@code ON CONFLICT DO UPDATE}를 쓰지 않는다. 발행된 자료 버전은 고치지 않는다(FR-017).
     */
    private long publishDataset(
            long mapVersionId, String datasetId, String deadline, boolean complete) {
        Long found =
                jdbcTemplate
                        .query(
                                "SELECT id FROM dataset_version WHERE dataset_id = ?",
                                (rs, row) -> rs.getLong("id"),
                                datasetId)
                        .stream()
                        .findFirst()
                        .orElse(null);
        if (found != null) return found;

        return jdbcTemplate.queryForObject(
                """
                INSERT INTO dataset_version
                    (dataset_id, data_deadline, collected_at, last_success_at,
                     complete, failure_reason, is_sample, source, map_version_id)
                VALUES (?, ?::timestamptz, ?::timestamptz, ?::timestamptz,
                        ?, ?, TRUE, 'pykrx', ?)
                RETURNING id
                """,
                Long.class,
                datasetId,
                deadline,
                deadline,
                deadline,
                complete,
                complete ? null : "이용 한도로 중간에 끊겼다",
                mapVersionId);
    }

    /**
     * 하루치 시세 한 행.
     *
     * <p>OHLC는 검사의 관심사가 아니지만 표의 제약({@code high >= max(open, low, close)})을 지켜야 들어간다. 그래서 종가를 기준으로 네
     * 값을 같게 둔다 — 하루 동안 값이 움직이지 않은 날과 같은 모양이며, 이 검사가 보는 수정종가에는 영향이 없다.
     */
    private void insertPrice(
            String datasetId,
            String instrumentId,
            String date,
            String adjustedClose,
            boolean suspended,
            boolean carriedForward) {
        jdbcTemplate.update(
                """
                INSERT INTO daily_price
                    (dataset_id, instrument_id, date, open, high, low, close,
                     volume, value, adjusted_close, adjustment_factor,
                     trading_suspended, price_carried_forward)
                VALUES (?, ?, ?::date, ?::numeric, ?::numeric, ?::numeric, ?::numeric,
                        0, 0, ?::numeric, 1, ?, ?)
                ON CONFLICT (dataset_id, instrument_id, date) DO NOTHING
                """,
                datasetId,
                instrumentId,
                date,
                adjustedClose,
                adjustedClose,
                adjustedClose,
                adjustedClose,
                adjustedClose,
                suspended,
                carriedForward);
    }
}
