package com.ssafy.thorp.sector;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.support.IntegrationTestSupport;
import java.time.LocalDate;
import java.util.Locale;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /sectors/evaluation-window} 계약 검증 (spec 001 T017, contracts/sector-api.md).
 *
 * <p>여기서 확인하는 것은 <b>실제 자료 위에서의 계약</b>이다 — 질의 문자열이 어떻게 읽히는지, 거래일을 {@code daily_index}에서 실제로 세는지, 응답의
 * 키 모양이 계약과 같은지, 그리고 없는 섹터·자료 미적재·평가 불가 셋이 서로 다른 응답으로 갈리는지다. 조립 규칙 자체는 {@code
 * EvaluationWindowQueryServiceTest}가 DB 없이 검증한다.
 *
 * <p><b>거래일 달력은 공통 준비 단계가 심는다.</b> {@code MarketCatalogSeed}가 2014~2026년의 평일을 <b>모든 자료 버전에</b> 넣어
 * 두므로 이 검사는 달력을 따로 심지 않는다. 그래서 여기서 기간을 좁히는 손잡이는 달력 길이가 아니라 <b>자료 마감일과 상장일</b>이다. 그 달력에 공휴일이 없으므로
 * 날짜는 평일로만 골랐다.
 *
 * <p><b>Spring 컨텍스트를 새로 만들지 않는다.</b> {@link IntegrationTestSupport}의 설정을 그대로 물려받아 캐시된 컨텍스트를 재사용한다.
 *
 * <p>자료를 넣는 경로가 JDBC인 이유는 대응표도 시계열도 원본이 파이썬 파이프라인이고 백엔드에 쓰기 경로가 없기 때문이다(research.md 결정 7).
 *
 * <p>Docker가 없으면 통째로 skip된다({@code PostgresTestSupport}). 결과의 {@code skipped}를 확인한다.
 */
class EvaluationWindowApiIT extends IntegrationTestSupport {

    /**
     * 실행마다 새로 짓는 꼬리표.
     *
     * <p><b>대응표 버전과 자료 버전을 고정 문자열로 둘 수 없다.</b> 두 표에는 UPDATE·DELETE를 막는 불변 트리거가 걸려 있어(FR-017) 한 번 넣은
     * 행을 지우지 못한다. 고정 이름을 쓰면 앞선 실행이 남긴 자료 버전이 같은 대응표에 그대로 붙어 있고, 조회는 그중 마감이 가장 늦은 것을 고르므로 <b>이번 실행이
     * 정한 마감일이 아니라 지난번 것을 보게 된다</b>. 실행마다 대응표를 새로 지으면 지난 행들은 다른 대응표에 붙은 채 조회 대상에서 빠진다.
     */
    private static final String RUN_ID =
            Long.toString(System.currentTimeMillis(), 36).toUpperCase(Locale.ROOT);

    private static final String MAP_VERSION = "SEM-EW-" + RUN_ID;
    private static final String MAP_VERSION_WITHOUT_DATASET = "SEM-EW-EMPTY-" + RUN_ID;
    private static final String DATASET_ID = "KRX_DAILY_EW_" + RUN_ID;

    private static final String SEMICONDUCTOR = "SEMICONDUCTOR_EW";
    private static final String BATTERY = "BATTERY_EW";
    private static final String SEMICONDUCTOR_ETF = "990001.XKRX";
    private static final String BATTERY_ETF = "990002.XKRX";

    /** 마감일 코앞에 상장한 후보. 어떤 전략의 워밍업도 마감 안에서 채우지 못해 "평가 불가"를 만든다. */
    private static final String LATE = "LATE_EW";

    private static final String LATE_ETF = "990003.XKRX";

    /** 이 검사의 자료 마감일. 달력은 2026년까지 있으므로 공통 종료일을 정하는 것은 이 값이다. */
    private static final LocalDate DEADLINE = LocalDate.of(2024, 6, 28);

    private static final LocalDate SEMICONDUCTOR_LISTED = LocalDate.of(2024, 1, 2);
    private static final LocalDate BATTERY_LISTED = LocalDate.of(2024, 2, 1);

    /** 마감일까지 거래일이 다섯 개뿐이다(6/24~6/28). */
    private static final LocalDate LATE_LISTED = LocalDate.of(2024, 6, 24);

    /** 워밍업 0일 때의 공통 시작일. 더 늦게 상장한 이차전지 후보가 정한다. */
    private static final LocalDate BASE = BATTERY_LISTED;

    /** 이차전지 후보의 자료 부족 구간. 기준일 직후라 기준일 기준 목록에는 늘 남는다. */
    private static final LocalDate GAP_START = LocalDate.of(2024, 2, 5);

    private static final LocalDate GAP_END = LocalDate.of(2024, 2, 7);

    /** 등록표의 기본 설정 워밍업이 60거래일이다(관측 기간 60 + 최근 제외 0). */
    private static final String MOMENTUM = "SECTOR_MOMENTUM";

    private static final int MOMENTUM_WARMUP = 60;

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        deleteFixtures();
        publishEverything();
    }

    @AfterEach
    void tearDown() {
        deleteFixtures();
    }

    @Test
    @DisplayName("전략 없이 상한만 주면 기준일과 그 뒤 거래일 목록을 준다")
    void 상한만_주면_재판정_재료를_준다() throws Exception {
        mockMvc.perform(
                        get("/sectors/evaluation-window")
                                .param("sectors", SEMICONDUCTOR + "," + BATTERY)
                                .param("maxWarmupTradingDays", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.evaluable").value(true))
                .andExpect(jsonPath("$.data.warmupTradingDays").value(0))
                .andExpect(jsonPath("$.data.warmupBaseDate").value(BASE.toString()))
                .andExpect(jsonPath("$.data.commonStart").value(BASE.toString()))
                .andExpect(jsonPath("$.data.commonEnd").value(DEADLINE.toString()))
                .andExpect(jsonPath("$.data.bindingCandidate").value(BATTERY_ETF))
                .andExpect(jsonPath("$.data.bindingSector").value(BATTERY))
                .andExpect(jsonPath("$.data.datasetId").value(DATASET_ID))
                .andExpect(jsonPath("$.data.maxWarmupTradingDays").value(20))
                .andExpect(jsonPath("$.data.tradingDaysFromBase", Matchers.hasSize(21)))
                .andExpect(jsonPath("$.data.tradingDaysFromBase[0]").value(BASE.toString()))
                .andExpect(jsonPath("$.data.excludedRangesFromBase", Matchers.hasSize(1)));
    }

    @Test
    @DisplayName("상한을 주지 않으면 재판정 재료의 키 자체가 없다")
    void 상한을_주지_않으면_키가_없다() throws Exception {
        mockMvc.perform(
                        get("/sectors/evaluation-window")
                                .param("sectors", SEMICONDUCTOR)
                                .param("strategy", MOMENTUM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evaluable").value(true))
                // 워밍업을 반영해 시작일이 상장일보다 뒤로 밀린다(FR-011).
                .andExpect(jsonPath("$.data.warmupTradingDays").value(MOMENTUM_WARMUP))
                .andExpect(
                        jsonPath("$.data.commonStart")
                                .value(Matchers.greaterThan(SEMICONDUCTOR_LISTED.toString())))
                .andExpect(jsonPath("$.data.tradingDaysFromBase").doesNotExist())
                .andExpect(jsonPath("$.data.maxWarmupTradingDays").doesNotExist())
                .andExpect(jsonPath("$.data.excludedRangesFromBase").doesNotExist())
                // 기준일은 상한과 무관하게 늘 있다. 화면이 "워밍업 때문에 줄었다"를 설명하는 값이다.
                .andExpect(
                        jsonPath("$.data.warmupBaseDate").value(SEMICONDUCTOR_LISTED.toString()));
    }

    @Test
    @DisplayName("달력이 요청한 상한에 모자라면 오류가 아니라 있는 만큼만 주고 상한을 줄인다")
    void 달력이_모자라면_상한을_줄인다() throws Exception {
        mockMvc.perform(
                        get("/sectors/evaluation-window")
                                .param("sectors", LATE)
                                .param("maxWarmupTradingDays", "5000"))
                .andExpect(status().isOk())
                // 상장일부터 마감일까지 거래일이 다섯 개라 상한은 넷이다.
                .andExpect(jsonPath("$.data.maxWarmupTradingDays").value(4))
                .andExpect(jsonPath("$.data.tradingDaysFromBase", Matchers.hasSize(5)))
                .andExpect(jsonPath("$.data.tradingDaysFromBase[0]").value(LATE_LISTED.toString()))
                .andExpect(jsonPath("$.data.tradingDaysFromBase[4]").value(DEADLINE.toString()));
    }

    @Test
    @DisplayName("자료 부족 구간은 워밍업 반영본과 기준일부터의 것을 함께 준다")
    void 부족_구간을_두_기준으로_준다() throws Exception {
        mockMvc.perform(
                        get("/sectors/evaluation-window")
                                .param("sectors", BATTERY)
                                .param("strategy", MOMENTUM)
                                .param("maxWarmupTradingDays", "30"))
                .andExpect(status().isOk())
                // 워밍업 60거래일을 반영한 시작일은 부족 구간보다 뒤라 잘려 나간다.
                .andExpect(jsonPath("$.data.excludedRanges", Matchers.hasSize(0)))
                // 화면이 더 짧은 워밍업으로 다시 판정하려면 그 앞의 부족 구간을 알아야 한다.
                .andExpect(jsonPath("$.data.excludedRangesFromBase", Matchers.hasSize(1)))
                .andExpect(
                        jsonPath("$.data.excludedRangesFromBase[0].start")
                                .value(GAP_START.toString()))
                .andExpect(
                        jsonPath("$.data.excludedRangesFromBase[0].end").value(GAP_END.toString()))
                .andExpect(
                        jsonPath("$.data.excludedRangesFromBase[0].reason")
                                .value("AMBIGUOUS_PRICE"));
    }

    @Test
    @DisplayName("평가할 수 없는 조합은 오류가 아니라 evaluable=false인 200이다")
    void 평가_불가는_오류가_아니다() throws Exception {
        mockMvc.perform(
                        get("/sectors/evaluation-window")
                                .param("sectors", LATE)
                                .param("strategy", MOMENTUM)
                                .param("maxWarmupTradingDays", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evaluable").value(false))
                // 그래도 재판정 재료는 그대로 준다 — 화면이 더 짧은 워밍업으로 다시 판정할 수 있어야 한다.
                .andExpect(jsonPath("$.data.warmupBaseDate").value(LATE_LISTED.toString()))
                .andExpect(jsonPath("$.data.tradingDaysFromBase", Matchers.hasSize(5)));
    }

    @Test
    @DisplayName("없는 섹터는 404, 전략도 상한도 없는 요청은 400")
    void 잘못된_요청을_가른다() throws Exception {
        mockMvc.perform(
                        get("/sectors/evaluation-window")
                                .param("sectors", "NO_SUCH_SECTOR")
                                .param("maxWarmupTradingDays", "10"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SECTOR_NOT_FOUND"));

        mockMvc.perform(get("/sectors/evaluation-window").param("sectors", SEMICONDUCTOR))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/sectors/evaluation-window").param("maxWarmupTradingDays", "10"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("쓸 수 있는 자료가 아직 없으면 빈 기간이 아니라 재시도 가능한 409다")
    void 자료가_없으면_빈_기간을_만들지_않는다() throws Exception {
        openMapWithoutDataset();

        mockMvc.perform(
                        get("/sectors/evaluation-window")
                                .param("sectors", SEMICONDUCTOR)
                                .param("maxWarmupTradingDays", "10"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EVALUATION_WINDOW_NOT_READY"));
    }

    // --- 픽스처 ------------------------------------------------------------

    /**
     * FK 역순으로 지운다.
     *
     * <p>{@code dataset_version}과 시계열에는 UPDATE·DELETE를 막는 불변 트리거가 걸려 있어(FR-017) 지우지 못한다. 그래서 자료 버전과
     * 부족 구간은 지우는 대신 <b>실행마다 새 식별자로</b> 만들고, 지울 수 있는 대응표·섹터만 치운다.
     *
     * <p>대응표는 반드시 닫는다. {@code effective_to IS NULL}인 행을 남기면 부분 UNIQUE 인덱스 때문에 다음 검사의 INSERT가 실패한다.
     */
    private void deleteFixtures() {
        jdbcTemplate.update(
                "UPDATE sector_etf_map_version"
                        + " SET effective_to = effective_from + INTERVAL '1 second'"
                        + " WHERE effective_to IS NULL");
        jdbcTemplate.update(
                "DELETE FROM sector_etf_map_entry WHERE sector_id IN"
                        + " (SELECT id FROM sector WHERE code IN (?, ?, ?))",
                SEMICONDUCTOR,
                BATTERY,
                LATE);
        jdbcTemplate.update(
                "DELETE FROM sector WHERE code IN (?, ?, ?)", SEMICONDUCTOR, BATTERY, LATE);
    }

    private void publishEverything() {
        long mapVersionId = publishMap();
        publishDataset(mapVersionId);
        publishCalendar();
        insertDataGap(BATTERY_ETF, GAP_START, GAP_END);
    }

    private long publishMap() {
        Long mapVersionId =
                jdbcTemplate.queryForObject(
                        """
                        INSERT INTO sector_etf_map_version
                            (version, effective_from, effective_to, is_sample, published_at)
                        VALUES (?, TIMESTAMPTZ '2026-09-17 00:00:00+09', NULL, TRUE,
                                TIMESTAMPTZ '2026-09-17 00:00:00+09')
                        ON CONFLICT (version) DO UPDATE SET effective_to = NULL
                        RETURNING id
                        """,
                        Long.class,
                        MAP_VERSION);
        insertEntry(
                mapVersionId,
                insertSector(SEMICONDUCTOR, "반도체"),
                etfId(SEMICONDUCTOR_ETF, "반도체 ETF", SEMICONDUCTOR_LISTED));
        insertEntry(
                mapVersionId,
                insertSector(BATTERY, "이차전지"),
                etfId(BATTERY_ETF, "이차전지 ETF", BATTERY_LISTED));
        insertEntry(
                mapVersionId,
                insertSector(LATE, "늦게 상장한 섹터"),
                etfId(LATE_ETF, "늦게 상장한 ETF", LATE_LISTED));
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

    private long etfId(String instrumentId, String name, LocalDate listedOn) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO representative_etf
                    (instrument_id, name, listed_on, benchmark_index, manager)
                VALUES (?, ?, ?, '기준 지수', '운용사')
                ON CONFLICT (instrument_id) DO UPDATE
                    SET name = EXCLUDED.name, listed_on = EXCLUDED.listed_on
                RETURNING id
                """,
                Long.class,
                instrumentId,
                name,
                listedOn);
    }

    private void insertEntry(long mapVersionId, long sectorId, long etfId) {
        jdbcTemplate.update(
                "INSERT INTO sector_etf_map_entry (map_version_id, sector_id, etf_id)"
                        + " VALUES (?, ?, ?)",
                mapVersionId,
                sectorId,
                etfId);
    }

    /** 자료 버전. 불변 트리거가 UPDATE를 막으므로 이미 있으면 그 행을 그대로 쓴다. */
    private void publishDataset(long mapVersionId) {
        Integer exists =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dataset_version WHERE dataset_id = ?",
                        Integer.class,
                        DATASET_ID);
        if (exists != null && exists > 0) {
            return;
        }
        String deadline = DEADLINE + "T18:00:00+09";
        jdbcTemplate.update(
                """
                INSERT INTO dataset_version
                    (dataset_id, data_deadline, collected_at, last_success_at, complete,
                     failure_reason, is_sample, source, map_version_id)
                VALUES (?, CAST(? AS TIMESTAMPTZ), CAST(? AS TIMESTAMPTZ), CAST(? AS TIMESTAMPTZ),
                        TRUE, NULL, TRUE, 'FIXED_TEST_DATA', ?)
                """,
                DATASET_ID,
                deadline,
                deadline,
                deadline,
                mapVersionId);
    }

    /**
     * 이 검사의 자료 버전에 거래일 달력을 심는다. 범위와 값은 {@code MarketCatalogSeed}와 같다.
     *
     * <p><b>공통 준비 단계가 이미 모든 자료 버전에 달력을 심는데도 다시 심는 까닭</b>: 그 준비는 상위 클래스의 {@code @BeforeEach}라 여기서 자료
     * 버전을 만들기 <b>전에</b> 돌고, 그 시점에 이 검사의 자료 버전은 아직 없다. 그래서 첫 검사만 달력을 못 보고 409로 끝난다 — 실행 순서에 따라 결과가
     * 달라지는 것이 이 가지의 위험이다.
     */
    private void publishCalendar() {
        jdbcTemplate.update(
                """
                INSERT INTO daily_index
                    (dataset_id, index_code, date, open, high, low, close, volume, value)
                SELECT ?, 'KOSPI', day::date, 2500, 2500, 2500, 2500, 0, 0
                  FROM generate_series(DATE '2014-01-01', DATE '2026-12-31', INTERVAL '1 day')
                       AS day
                 WHERE EXTRACT(ISODOW FROM day) < 6
                ON CONFLICT (dataset_id, index_code, date) DO NOTHING
                """,
                DATASET_ID);
    }

    private void insertDataGap(String instrumentId, LocalDate start, LocalDate end) {
        jdbcTemplate.update(
                """
                INSERT INTO data_gap (dataset_id, instrument_id, start_date, end_date, reason)
                VALUES (?, ?, ?, ?, 'AMBIGUOUS_PRICE')
                ON CONFLICT DO NOTHING
                """,
                DATASET_ID,
                instrumentId,
                start,
                end);
    }

    /**
     * 자료 버전이 붙지 않은 대응표를 새로 연다. 섹터 연결은 지금 열린 대응표의 것을 그대로 옮긴다.
     *
     * <p>"자료를 지운다"로 같은 상태를 만들지 못한다 — 자료 버전에는 DELETE를 막는 불변 트리거가 걸려 있다(FR-017). 대응표를 갈아 끼우면 검사가 끝난 뒤
     * {@code deleteFixtures}가 열린 버전을 닫으면서 원래대로 돌아간다.
     */
    private void openMapWithoutDataset() {
        Long previousId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM sector_etf_map_version WHERE version = ?",
                        Long.class,
                        MAP_VERSION);
        jdbcTemplate.update(
                "UPDATE sector_etf_map_version"
                        + " SET effective_to = effective_from + INTERVAL '1 second'"
                        + " WHERE effective_to IS NULL");
        Long emptyMapId =
                jdbcTemplate.queryForObject(
                        """
                        INSERT INTO sector_etf_map_version
                            (version, effective_from, effective_to, is_sample, published_at)
                        VALUES (?, TIMESTAMPTZ '2026-09-18 00:00:00+09', NULL, TRUE,
                                TIMESTAMPTZ '2026-09-18 00:00:00+09')
                        ON CONFLICT (version) DO UPDATE SET effective_to = NULL
                        RETURNING id
                        """,
                        Long.class,
                        MAP_VERSION_WITHOUT_DATASET);
        jdbcTemplate.update(
                """
                INSERT INTO sector_etf_map_entry (map_version_id, sector_id, etf_id)
                SELECT ?, sector_id, etf_id FROM sector_etf_map_entry WHERE map_version_id = ?
                ON CONFLICT DO NOTHING
                """,
                emptyMapId,
                previousId);
    }
}
