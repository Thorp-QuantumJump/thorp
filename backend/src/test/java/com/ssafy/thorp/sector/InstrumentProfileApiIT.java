package com.ssafy.thorp.sector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /sectors/{code}/instrument-profile} 계약 검증 (spec 001 T013, contracts/sector-api.md).
 *
 * <p>확인하는 것은 다섯 가지다 — 응답이 계약 예시와 같은지, 필수 항목이 모두 있는지(FR-015), <b>추적 차이 필드가 없는지</b>(PD-21), 과거 구성 이력이
 * 없을 때 현재 구성 기준 표시가 붙는지, 그리고 없는 섹터(404)와 자료 미적재(409)가 구분되는지다.
 *
 * <p><b>Spring 컨텍스트를 새로 만들지 않는다.</b> {@code @SpringBootTest} 속성과 {@code @AutoConfigureMockMvc}를
 * {@code SectorApiIT}와 글자 그대로 같게 맞춰 두었다. 속성 하나라도 바꾸면 컨텍스트가 갈라지고, 이 저장소의 DB 테스트 묶음은 이미 공용 컨테이너의
 * {@code max_connections}를 거의 다 쓰고 있어 나중에 뜨는 다른 DB 테스트가 접속을 얻지 못한다. <b>이 애노테이션 묶음은 건드리지 않는다.</b>
 *
 * <p>자료를 넣는 경로가 JDBC인 이유: 대응표도 대표 상품 정보도 원본은 파이썬 파이프라인이고 백엔드에는 쓰기 경로가 아예 없다(research.md 결정 7). 저장소
 * 인터페이스로 픽스처를 만들 수 없는 것이 정상이다.
 *
 * <p>Docker가 없으면 통째로 skip된다({@code PostgresTestSupport}). 결과의 {@code skipped}를 확인한다.
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
class InstrumentProfileApiIT extends PostgresTestSupport {

    private static final String MAP_VERSION = "SEM-2026-09-13-001";
    private static final String SEMICONDUCTOR = "SEMICONDUCTOR";
    private static final String BANK = "BANK";
    private static final String SEMICONDUCTOR_ETF = "091160.XKRX";
    private static final String BANK_ETF = "091170.XKRX";
    private static final String DATASET_ID = "KRX_DAILY_20260907";
    private static final String OLD_DATASET_ID = "KRX_DAILY_20260801";
    private static final String PARTIAL_DATASET_ID = "KRX_DAILY_20260908";
    private static final String RETIRED_MAP_VERSION = "SEM-2026-08-01-001";
    private static final String RETIRED_DATASET_ID = "KRX_DAILY_20260930";

    private static final String METHODOLOGY = "KRX 반도체 지수를 추종하며 유동시가총액 가중으로 구성종목을 담는다.";
    private static final String SOURCE_ATTRIBUTION = "한국거래소 통계정보";
    private static final String SURVIVORSHIP_NOTE = "현재 상장되어 있는 상품만 다루므로 중간에 상장폐지된 상품은 포함되지 않습니다.";

    /** contracts/sector-api.md 예시의 {@code holdingsOverlap} 그대로다. */
    private static final String CONTRACT_EXAMPLE_OVERLAP =
            """
            {
              "asOfCurrentComposition": true,
              "topHoldings": [
                { "name": "삼성전자", "weight": "0.2431" },
                { "name": "SK하이닉스", "weight": "0.2107" }
              ]
            }
            """;

    private static final String HOLDINGS_JSON =
            """
            {"topHoldings":[{"name":"삼성전자","weight":"0.2431"},
                            {"name":"SK하이닉스","weight":"0.2107"}]}
            """;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        deleteFixtures();
    }

    @AfterEach
    void restore() {
        deleteFixtures();
    }

    /**
     * 픽스처를 FK 역순으로 지운다.
     *
     * <p>{@code dataset_version}에는 UPDATE·DELETE를 거부하는 불변 트리거가 걸려 있다(FR-017). 그래서 그 표는 지우지 못하고, 대신
     * 테스트마다 새로 쓰지 않도록 {@code dataset_id}를 이 클래스 전용 값으로 두고 이미 있으면 다시 넣지 않는다. {@code
     * instrument_profile}에는 트리거가 없어 지울 수 있다 — 대조 기록과 같은 판단이며 근거는 마이그레이션 머리말에 적었다.
     *
     * <p>대응표는 반드시 비운다. {@code effective_to IS NULL}인 행을 남기면 부분 UNIQUE 인덱스 때문에 다음 테스트의 INSERT가 실패한다.
     */
    private void deleteFixtures() {
        jdbcTemplate.update(
                "DELETE FROM instrument_profile WHERE etf_id IN"
                        + " (SELECT id FROM representative_etf WHERE instrument_id IN (?, ?))",
                SEMICONDUCTOR_ETF,
                BANK_ETF);
        jdbcTemplate.update(
                "DELETE FROM sector_etf_map_entry WHERE map_version_id IN"
                        + " (SELECT id FROM sector_etf_map_version WHERE version = ?)",
                MAP_VERSION);
        // 자료 버전은 대응표 버전을 참조하므로 대응표 버전 행은 지우지 않고 닫아 둔다. 트리거가
        // dataset_version의 DELETE를 막아 FK 때문에 지울 수 없기 때문이다.
        // 자기 버전만이 아니라 **열려 있는 모든 버전**을 닫는다. 공통 준비 단계가 심는 씨앗도 하나를
        // 열어 두는데, 열린 버전은 부분 UNIQUE 인덱스가 하나로 강제하므로 남겨 두면 이 검사가 자기
        // 대응표를 열지 못한다.
        jdbcTemplate.update(
                "UPDATE sector_etf_map_version"
                        + " SET effective_to = effective_from + INTERVAL '1 second'"
                        + " WHERE effective_to IS NULL");
        // 씨앗의 대응표 항목도 같은 섹터를 참조한다. 남기면 섹터를 지울 때 외래키에 걸린다.
        jdbcTemplate.update(
                "DELETE FROM sector_etf_map_entry WHERE sector_id IN"
                        + " (SELECT id FROM sector WHERE code IN (?, ?))",
                SEMICONDUCTOR,
                BANK);
        jdbcTemplate.update("DELETE FROM sector WHERE code IN (?, ?)", SEMICONDUCTOR, BANK);
    }

    /** 열린 대응표 하나와 섹터 둘. 반환값은 대응표 버전의 PK다. */
    private long publishMap() {
        Long mapVersionId =
                jdbcTemplate.queryForObject(
                        """
                        INSERT INTO sector_etf_map_version
                            (version, effective_from, effective_to, is_sample, published_at)
                        VALUES (?, TIMESTAMPTZ '2026-09-13 00:00:00+09', NULL, TRUE,
                                TIMESTAMPTZ '2026-09-13 00:00:00+09')
                        ON CONFLICT (version) DO UPDATE SET effective_to = NULL
                        RETURNING id
                        """,
                        Long.class,
                        MAP_VERSION);
        insertEntry(mapVersionId, insertSector(SEMICONDUCTOR, "반도체"), etfId(SEMICONDUCTOR_ETF));
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

    /** 대표 ETF는 자료 버전처럼 지울 수 없는 표가 아니지만 여러 테스트가 공유하므로 있으면 그대로 쓴다. */
    private long etfId(String instrumentId) {
        String name = SEMICONDUCTOR_ETF.equals(instrumentId) ? "KODEX 반도체" : "KODEX 은행";
        String index = SEMICONDUCTOR_ETF.equals(instrumentId) ? "KRX 반도체" : "KRX 은행";
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO representative_etf
                    (instrument_id, name, listed_on, benchmark_index, manager)
                VALUES (?, ?, DATE '2006-06-27', ?, '삼성자산운용')
                ON CONFLICT (instrument_id) DO UPDATE
                    SET name = EXCLUDED.name,
                        listed_on = EXCLUDED.listed_on,
                        benchmark_index = EXCLUDED.benchmark_index
                RETURNING id
                """,
                Long.class,
                instrumentId,
                name,
                index);
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
     * <p>{@code ON CONFLICT DO NOTHING}이 아니라 {@code DO UPDATE ... failure_reason} 같은 갱신을 쓰지 않는 이유는
     * 불변 트리거가 UPDATE를 거부하기 때문이다. 발행된 자료 버전은 고치지 않는다(FR-017).
     */
    private long publishDataset(
            long mapVersionId, String datasetId, String deadline, boolean done) {
        Long found =
                jdbcTemplate
                        .query(
                                "SELECT id FROM dataset_version WHERE dataset_id = ?",
                                (rs, row) -> rs.getLong("id"),
                                datasetId)
                        .stream()
                        .findFirst()
                        .orElse(null);
        if (found != null) {
            return found;
        }
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO dataset_version
                    (dataset_id, data_deadline, collected_at, last_success_at, complete,
                     failure_reason, is_sample, source, map_version_id)
                VALUES (?, CAST(? AS TIMESTAMPTZ), CAST(? AS TIMESTAMPTZ), CAST(? AS TIMESTAMPTZ),
                        ?, ?, TRUE, 'FIXED_TEST_DATA', ?)
                RETURNING id
                """,
                Long.class,
                datasetId,
                deadline,
                deadline,
                deadline,
                done,
                done ? null : "이용 한도 도달",
                mapVersionId);
    }

    /**
     * 이미 물러난 대응표 버전 하나. {@code effective_to}가 차 있어 열린 버전이 아니다.
     *
     * <p>열린 버전은 최대 하나라는 불변식이 있으므로 이 행은 그 제약에 걸리지 않는다. 섹터 연결을 만들지 않는 이유는 대표 상품 정보 조회가 대응표 항목이 아니라 자료
     * 버전이 가리키는 대응표 버전만 보기 때문이다.
     */
    private long publishRetiredMap() {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO sector_etf_map_version
                    (version, effective_from, effective_to, is_sample, published_at)
                VALUES (?, TIMESTAMPTZ '2026-08-01 00:00:00+09', TIMESTAMPTZ '2026-09-13 00:00:00+09',
                        TRUE, TIMESTAMPTZ '2026-08-01 00:00:00+09')
                ON CONFLICT (version) DO UPDATE
                    SET effective_to = TIMESTAMPTZ '2026-09-13 00:00:00+09'
                RETURNING id
                """,
                Long.class,
                RETIRED_MAP_VERSION);
    }

    private void insertProfile(long etfId, long datasetVersionId, String value, boolean current) {
        jdbcTemplate.update(
                """
                INSERT INTO instrument_profile
                    (etf_id, dataset_version_id, index_methodology_summary, holdings_overlap,
                     holdings_as_of_current, avg_daily_value_3m, source_attribution,
                     survivorship_note)
                VALUES (?, ?, ?, CAST(? AS JSONB), ?, ?, ?, ?)
                """,
                etfId,
                datasetVersionId,
                METHODOLOGY,
                HOLDINGS_JSON,
                current,
                value,
                SOURCE_ATTRIBUTION,
                SURVIVORSHIP_NOTE);
    }

    /** 계약 예시와 같은 상태를 만든다. 반도체 섹터에만 대표 상품 정보가 있다. */
    private void publishEverything() {
        long mapVersionId = publishMap();
        long datasetVersionId =
                publishDataset(mapVersionId, DATASET_ID, "2026-09-07T18:00:00+09", true);
        insertProfile(etfId(SEMICONDUCTOR_ETF), datasetVersionId, "1240000000", true);
    }

    private JsonNode getJson(String url, int expectedStatus) throws Exception {
        String body =
                mockMvc.perform(get(url))
                        .andExpect(status().is(expectedStatus))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }

    private JsonNode profileOf(String code) throws Exception {
        return getJson("/sectors/" + code + "/instrument-profile", 200).get("data");
    }

    @Test
    @DisplayName("응답이 계약 예시와 같다 — 필수 항목이 전부 있다 (FR-015)")
    void responseMatchesTheContractExample() throws Exception {
        publishEverything();

        JsonNode data = profileOf(SEMICONDUCTOR);

        assertThat(data.get("instrumentId").asText()).isEqualTo(SEMICONDUCTOR_ETF);
        assertThat(data.get("name").asText()).isEqualTo("KODEX 반도체");
        assertThat(data.get("benchmarkIndex").asText()).isEqualTo("KRX 반도체");
        assertThat(data.get("indexMethodologySummary").asText()).isEqualTo(METHODOLOGY);
        assertThat(data.get("listedOn").asText()).isEqualTo("2006-06-27");
        assertThat(data.get("manager").asText()).isEqualTo("삼성자산운용");
        assertThat(data.get("holdingsOverlap"))
                .isEqualTo(objectMapper.readTree(CONTRACT_EXAMPLE_OVERLAP));
        assertThat(data.get("sourceAttribution").asText()).isEqualTo(SOURCE_ATTRIBUTION);
        assertThat(data.get("survivorshipNote").asText()).isEqualTo(SURVIVORSHIP_NOTE);
        assertThat(data.get("datasetId").asText()).isEqualTo(DATASET_ID);
        assertThat(data.get("dataDeadline").asText()).isEqualTo("2026-09-07T09:00:00Z");
        assertThat(data.get("isSample").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("필수 항목이 하나도 빠지지 않는다 — 계약이 정한 키 집합과 정확히 같다")
    void carriesExactlyTheContractFields() throws Exception {
        publishEverything();

        JsonNode data = profileOf(SEMICONDUCTOR);

        List<String> actual = new ArrayList<>();
        data.fieldNames().forEachRemaining(actual::add);
        assertThat(actual)
                .containsExactlyInAnyOrder(
                        "instrumentId",
                        "name",
                        "benchmarkIndex",
                        "indexMethodologySummary",
                        "listedOn",
                        "manager",
                        "holdingsOverlap",
                        "avgDailyValue3m",
                        "sourceAttribution",
                        "survivorshipNote",
                        "datasetId",
                        "dataDeadline",
                        "isSample");
    }

    @Test
    @DisplayName("추적 차이 필드가 없다 — 계산 정의가 확정되지 않아 범위에서 뺐다 (PD-21)")
    void hasNoTrackingDifferenceField() throws Exception {
        publishEverything();

        JsonNode data = profileOf(SEMICONDUCTOR);

        data.fieldNames()
                .forEachRemaining(
                        field ->
                                assertThat(field.toLowerCase())
                                        .as("추적 차이 필드를 내보내면 검증할 수 없는 숫자를 보여주게 된다")
                                        .doesNotContain("tracking")
                                        .doesNotContain("difference"));
    }

    @Test
    @DisplayName("금액과 비중은 지수 표기 없는 문자열이다 — 전역 등록이 아니라 필드마다 붙인다")
    void amountsAreSerializedAsPlainStrings() throws Exception {
        long mapVersionId = publishMap();
        long datasetVersionId =
                publishDataset(mapVersionId, DATASET_ID, "2026-09-07T18:00:00+09", true);
        insertProfile(etfId(SEMICONDUCTOR_ETF), datasetVersionId, "1240000000", true);

        JsonNode data = profileOf(SEMICONDUCTOR);

        assertThat(data.get("avgDailyValue3m").isTextual()).isTrue();
        assertThat(data.get("avgDailyValue3m").asText()).isEqualTo("1240000000");
        JsonNode weight = data.get("holdingsOverlap").get("topHoldings").get(0).get("weight");
        assertThat(weight.isTextual()).isTrue();
        assertThat(weight.asText()).isEqualTo("0.2431");
    }

    @Test
    @DisplayName("다른 도메인의 숫자 계약은 그대로다 — 웹 ObjectMapper에 전역 등록하지 않았다")
    void doesNotBreakOtherDomainsNumberContract() throws Exception {
        // MarketResponse.value가 문자열로 바뀌면 그 API 계약이 깨진다(backend/CLAUDE.md).
        // 전역 등록 여부는 ObjectMapper에게 직접 물어본다 — 다른 도메인의 자료를 만들지 않아도 된다.
        String rendered = objectMapper.writeValueAsString(new BigDecimal("1.50"));

        assertThat(rendered).isEqualTo("1.50");
    }

    @Test
    @DisplayName("과거 구성 이력이 없으면 현재 구성 기준임이 표시된다 (FR-015)")
    void marksCompositionAsOfCurrent() throws Exception {
        publishEverything();

        JsonNode overlap = profileOf(SEMICONDUCTOR).get("holdingsOverlap");

        assertThat(overlap.get("asOfCurrentComposition").asBoolean()).isTrue();
        assertThat(overlap.get("topHoldings")).hasSize(2);
    }

    @Test
    @DisplayName("가장 최근 자료 버전의 값을 보여준다 — 옛 버전의 행은 재현용으로 남을 뿐이다")
    void showsTheNewestDatasetVersion() throws Exception {
        long mapVersionId = publishMap();
        long oldVersionId =
                publishDataset(mapVersionId, OLD_DATASET_ID, "2026-08-01T18:00:00+09", true);
        long newVersionId =
                publishDataset(mapVersionId, DATASET_ID, "2026-09-07T18:00:00+09", true);
        long etfId = etfId(SEMICONDUCTOR_ETF);
        insertProfile(etfId, oldVersionId, "900000000", true);
        insertProfile(etfId, newVersionId, "1240000000", true);

        JsonNode data = profileOf(SEMICONDUCTOR);

        assertThat(data.get("datasetId").asText()).isEqualTo(DATASET_ID);
        assertThat(data.get("avgDailyValue3m").asText()).isEqualTo("1240000000");
    }

    @Test
    @DisplayName("부분만 갱신된 자료 버전은 쓰지 않는다 (FR-014)")
    void ignoresIncompleteDatasetVersions() throws Exception {
        long mapVersionId = publishMap();
        long completeId = publishDataset(mapVersionId, DATASET_ID, "2026-09-07T18:00:00+09", true);
        long partialId =
                publishDataset(mapVersionId, PARTIAL_DATASET_ID, "2026-09-08T18:00:00+09", false);
        long etfId = etfId(SEMICONDUCTOR_ETF);
        insertProfile(etfId, completeId, "1240000000", true);
        // 마감 시각은 이쪽이 더 늦지만 부분 갱신이라 고르지 않는다.
        insertProfile(etfId, partialId, "999999999", true);

        assertThat(profileOf(SEMICONDUCTOR).get("datasetId").asText()).isEqualTo(DATASET_ID);
    }

    @Test
    @DisplayName("물러난 대응표로 만든 자료 버전은 고르지 않는다")
    void ignoresProfilesFromARetiredMapVersion() throws Exception {
        long openMapId = publishMap();
        long openVersionId = publishDataset(openMapId, DATASET_ID, "2026-09-07T18:00:00+09", true);
        long retiredMapId = publishRetiredMap();
        // 마감 시각을 일부러 더 늦게 둔다. 대응표 조건을 지우면 정렬이 이쪽을 고르므로 이 테스트가
        // 깨진다. 조건이 있는지를 값으로 확인하려는 것이다.
        long retiredVersionId =
                publishDataset(retiredMapId, RETIRED_DATASET_ID, "2026-09-30T18:00:00+09", true);
        long etfId = etfId(SEMICONDUCTOR_ETF);
        insertProfile(etfId, openVersionId, "1240000000", true);
        insertProfile(etfId, retiredVersionId, "999999999", true);

        JsonNode data = profileOf(SEMICONDUCTOR);

        assertThat(data.get("datasetId").asText()).isEqualTo(DATASET_ID);
        assertThat(data.get("avgDailyValue3m").asText()).isEqualTo("1240000000");
    }

    @Test
    @DisplayName("없는 섹터 코드는 404 SECTOR_NOT_FOUND다")
    void unknownSectorIsNotFound() throws Exception {
        publishEverything();

        mockMvc.perform(get("/sectors/NO_SUCH_SECTOR/instrument-profile"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("SECTOR_NOT_FOUND"));
    }

    @Test
    @DisplayName("형식이 틀린 코드도 404다 — 500이 되면 클라이언트 입력으로 5xx 경보가 울린다")
    void malformedSectorCodeIsNotFound() throws Exception {
        publishEverything();

        mockMvc.perform(get("/sectors/not-a-code/instrument-profile"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SECTOR_NOT_FOUND"));
    }

    @Test
    @DisplayName("자료가 아직 없으면 빈 값이 아니라 409 INSTRUMENT_PROFILE_NOT_READY다 (원칙 VI)")
    void missingProfileIsConflictNotEmpty() throws Exception {
        publishEverything();

        mockMvc.perform(get("/sectors/" + BANK + "/instrument-profile"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("INSTRUMENT_PROFILE_NOT_READY"));
    }

    @Test
    @DisplayName("대응표가 발행되지 않았으면 409 SECTOR_MAP_NOT_READY다 — 섹터 없음과 구분한다")
    void missingMapIsReportedSeparately() throws Exception {
        mockMvc.perform(get("/sectors/" + SEMICONDUCTOR + "/instrument-profile"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SECTOR_MAP_NOT_READY"));
    }

    @Test
    @DisplayName("자료 버전마다 대표 ETF 한 건이다 — 두 번째 행은 저장이 거부된다")
    void oneProfilePerEtfAndDatasetVersion() {
        long mapVersionId = publishMap();
        long datasetVersionId =
                publishDataset(mapVersionId, DATASET_ID, "2026-09-07T18:00:00+09", true);
        long etfId = etfId(SEMICONDUCTOR_ETF);
        insertProfile(etfId, datasetVersionId, "1240000000", true);

        // 같은 (etf_id, dataset_version_id)를 한 번 더 넣으면 UNIQUE 위반이다. 이 제약이
        // "값을 고치려면 새 자료 버전"을 강제하므로 불변 트리거를 따로 걸지 않는다(SC-004).
        assertThatThrownBy(() -> insertProfile(etfId, datasetVersionId, "999", true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("금액은 지수 표기로 저장할 수 없다 — 읽는 쪽이 그대로 BigDecimal로 옮긴다")
    void rejectsAmountsInExponentNotation() {
        long mapVersionId = publishMap();
        long datasetVersionId =
                publishDataset(mapVersionId, DATASET_ID, "2026-09-07T18:00:00+09", true);
        long etfId = etfId(SEMICONDUCTOR_ETF);

        assertThatThrownBy(() -> insertProfile(etfId, datasetVersionId, "1.24E+9", true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("상위 종목이 빈 목록이면 저장이 거부된다 — 빈 목록은 '중복이 없다'로 읽힌다 (원칙 VI)")
    void rejectsEmptyTopHoldings() {
        long mapVersionId = publishMap();
        long datasetVersionId =
                publishDataset(mapVersionId, DATASET_ID, "2026-09-07T18:00:00+09", true);
        long etfId = etfId(SEMICONDUCTOR_ETF);

        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        """
                                        INSERT INTO instrument_profile
                                            (etf_id, dataset_version_id,
                                             index_methodology_summary, holdings_overlap,
                                             holdings_as_of_current, avg_daily_value_3m,
                                             source_attribution, survivorship_note)
                                        VALUES (?, ?, ?, CAST('{"topHoldings":[]}' AS JSONB),
                                                TRUE, '1', ?, ?)
                                        """,
                                        etfId,
                                        datasetVersionId,
                                        METHODOLOGY,
                                        SOURCE_ATTRIBUTION,
                                        SURVIVORSHIP_NOTE))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
