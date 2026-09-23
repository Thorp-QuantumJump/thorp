package com.ssafy.thorp.sector;

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
 * {@code GET /sectors/{code}/price-series} 계약 검증.
 *
 * <p>확인하는 것은 넷이다 — 점이 날짜 오름차순으로 오는지, 이어 붙인 가격과 거래정지일이 표시와 함께 오는지, 부분 갱신된 자료 버전을 고르지 않는지, 그리고 없는
 * 섹터(404)와 가격 미적재(409)가 구분되는지다.
 *
 * <p>자료를 넣는 경로가 JDBC인 이유는 {@code InstrumentProfileApiIT}와 같다. 대응표도 시세도 원본은 파이썬 파이프라인이고 백엔드에는 쓰기 경로가
 * 아예 없다(research.md 결정 7).
 *
 * <p>Docker가 없으면 통째로 skip된다({@code PostgresTestSupport}). 결과의 {@code skipped}를 확인한다.
 */
class PriceSeriesApiIT extends IntegrationTestSupport {

    /**
     * 검사마다 자기 대응표와 자기 자료 버전을 쓴다.
     *
     * <p>{@code daily_price}와 {@code dataset_version}에는 UPDATE·DELETE를 거부하는 불변 트리거가 걸려 있어 (FR-017)
     * 검사가 끝나도 행이 남는다. 모두가 한 자료 버전을 함께 쓰면 앞선 검사가 넣어 둔 날짜가 다음 검사의 선에 섞이고, 자료 버전만 갈라도 "그 대응표의 가장 늦은
     * 완전한 자료"를 고르는 규칙 때문에 다른 검사의 자료가 뽑힌다. 대응표까지 갈라야 서로를 보지 않는다.
     */
    private static final String ORDER_MAP = "SEM-PS-ORDER";

    private static final String FLAGS_MAP = "SEM-PS-FLAGS";
    private static final String PARTIAL_MAP = "SEM-PS-PARTIAL";
    private static final String UNKNOWN_MAP = "SEM-PS-UNKNOWN";
    private static final String MISSING_MAP = "SEM-PS-MISSING";

    private static final String CONSTRUCTION = "CONSTRUCTION_PS";
    private static final String BANK = "BANK_PS";
    private static final String CONSTRUCTION_ETF = "117700.XKRX";
    private static final String BANK_ETF = "091170.XKRX";

    /** 수정종가는 {@code NUMERIC(20, 8)}이라 응답에도 소수 여덟 자리로 나온다. */
    private static final String TEN_THOUSAND = "10000.00000000";

    private static final String TEN_THOUSAND_TWO_FIFTY = "10250.50000000";

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
    @DisplayName("고른 섹터 대표 ETF의 일별 수정종가가 날짜 오름차순으로 온다")
    void returnsPointsInDateOrder() throws Exception {
        String datasetId = "KRX_DAILY_PS_ORDER";
        long mapVersionId = publishMap(ORDER_MAP);
        publishDataset(mapVersionId, datasetId, "2026-09-22 15:30:00+09", true);
        // 일부러 뒤섞어 넣는다. 정렬을 저장 순서에 기대고 있으면 여기서 드러난다.
        insertPrice(datasetId, CONSTRUCTION_ETF, "2026-09-18", "10500.0000", false, false);
        insertPrice(datasetId, CONSTRUCTION_ETF, "2026-09-16", "10000.0000", false, false);
        insertPrice(datasetId, CONSTRUCTION_ETF, "2026-09-17", "10250.5000", false, false);

        mockMvc.perform(get("/sectors/{code}/price-series", CONSTRUCTION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.instrumentId").value(CONSTRUCTION_ETF))
                .andExpect(jsonPath("$.data.datasetId").value(datasetId))
                .andExpect(jsonPath("$.data.isSample").value(true))
                .andExpect(jsonPath("$.data.points.length()").value(3))
                .andExpect(jsonPath("$.data.points[0].date").value("2026-09-16"))
                .andExpect(jsonPath("$.data.points[1].date").value("2026-09-17"))
                .andExpect(jsonPath("$.data.points[2].date").value("2026-09-18"))
                // 값을 가공하지 않는다. 지수 표기로 바뀌면 화면이 다시 읽어야 한다.
                .andExpect(
                        jsonPath("$.data.points[1].adjustedClose").value(TEN_THOUSAND_TWO_FIFTY));
    }

    @Test
    @DisplayName("거래정지일과 이어 붙인 가격을 숨기지 않고 표시와 함께 보낸다")
    void keepsSuspendedAndCarriedForwardFlags() throws Exception {
        String datasetId = "KRX_DAILY_PS_FLAGS";
        long mapVersionId = publishMap(FLAGS_MAP);
        publishDataset(mapVersionId, datasetId, "2026-09-22 15:30:00+09", true);
        insertPrice(datasetId, CONSTRUCTION_ETF, "2026-09-16", "10000.0000", false, false);
        // 거래가 멈춘 날이다. 그 날 수정종가는 직전 값을 옮겨 적은 것이라 "그 값에 살 수 있었다"가 아니다.
        insertPrice(datasetId, CONSTRUCTION_ETF, "2026-09-17", "10000.0000", true, true);

        mockMvc.perform(get("/sectors/{code}/price-series", CONSTRUCTION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.points[0].tradingSuspended").value(false))
                .andExpect(jsonPath("$.data.points[0].priceCarriedForward").value(false))
                .andExpect(jsonPath("$.data.points[1].tradingSuspended").value(true))
                .andExpect(jsonPath("$.data.points[1].priceCarriedForward").value(true));
    }

    @Test
    @DisplayName("부분 갱신된 자료 버전은 고르지 않는다 (FR-014)")
    void ignoresIncompleteDataset() throws Exception {
        String fullDatasetId = "KRX_DAILY_PS_FULL";
        String partialDatasetId = "KRX_DAILY_PS_PARTIAL";
        long mapVersionId = publishMap(PARTIAL_MAP);
        publishDataset(mapVersionId, fullDatasetId, "2026-09-22 15:30:00+09", true);
        // 마감 시각이 더 늦지만 중간에 끊긴 자료다. 이것으로 그린 선은 있었던 구간을 없었던 것처럼 보인다.
        publishDataset(mapVersionId, partialDatasetId, "2026-09-23 15:30:00+09", false);
        insertPrice(fullDatasetId, CONSTRUCTION_ETF, "2026-09-16", "10000.0000", false, false);
        insertPrice(partialDatasetId, CONSTRUCTION_ETF, "2026-09-16", "99999.0000", false, false);

        mockMvc.perform(get("/sectors/{code}/price-series", CONSTRUCTION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.datasetId").value(fullDatasetId))
                .andExpect(jsonPath("$.data.points[0].adjustedClose").value(TEN_THOUSAND));
    }

    @Test
    @DisplayName("열린 대응표에 없는 섹터는 404다")
    void unknownSectorIsNotFound() throws Exception {
        long mapVersionId = publishMap(UNKNOWN_MAP);
        publishDataset(mapVersionId, "KRX_DAILY_PS_UNKNOWN", "2026-09-22 15:30:00+09", true);

        mockMvc.perform(get("/sectors/{code}/price-series", "NO_SUCH_SECTOR"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SECTOR_NOT_FOUND"));
    }

    @Test
    @DisplayName("섹터는 있는데 가격이 아직 없으면 빈 배열이 아니라 409다")
    void missingPricesIsConflict() throws Exception {
        String datasetId = "KRX_DAILY_PS_MISSING";
        long mapVersionId = publishMap(MISSING_MAP);
        publishDataset(mapVersionId, datasetId, "2026-09-22 15:30:00+09", true);
        // 건설만 적재하고 은행은 비워 둔다. 빈 선을 돌려주면 "움직임이 없었다"로 읽힌다.
        insertPrice(datasetId, CONSTRUCTION_ETF, "2026-09-16", "10000.0000", false, false);

        mockMvc.perform(get("/sectors/{code}/price-series", BANK))
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
