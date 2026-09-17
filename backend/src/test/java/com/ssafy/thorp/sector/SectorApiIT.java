package com.ssafy.thorp.sector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /sectors} 계약 검증 (spec 001 T006, contracts/sector-api.md).
 *
 * <p>확인하는 것은 세 가지다 — 응답이 계약 예시와 항목까지 같은지, 대표 ETF가 빠진 항목이 없는지(SC-001), 그리고 <b>대응표가 없을 때 빈 배열이 아니라
 * 409가 나오는지</b>(원칙 VI).
 *
 * <p><b>Spring 컨텍스트를 새로 만들지 않는다.</b> {@code @SpringBootTest} 속성과 {@code @AutoConfigureMockMvc}를
 * {@code BacktestResultApiIT}와 글자 그대로 같게 맞춰 두었다. Spring은 설정이 같으면 컨텍스트를 캐시에서 재사용하므로 Hikari 풀이 늘지
 * 않는다. 이 저장소의 DB 테스트 묶음은 이미 공용 컨테이너의 {@code max_connections}(기본 100)를 거의 다 쓰고 있어서, 컨텍스트를 하나 더 만들면 이
 * 클래스가 아니라 나중에 뜨는 다른 DB 테스트가 {@code FATAL: sorry, too many clients already}로 죽는다({@code
 * EtfMarketDataSchemaIT}의 주석과 같은 사정). <b>속성 하나라도 바꾸면 컨텍스트가 갈라지므로 이 애노테이션 묶음은 건드리지 않는다.</b>
 *
 * <p>대응표를 넣는 경로가 JDBC인 이유: 대응표의 원본은 파이썬 파이프라인이고 백엔드에는 쓰기 경로가 아예 없다(research.md 결정 7). 저장소 인터페이스로는
 * 픽스처를 만들 수 없는 것이 정상이다.
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
class SectorApiIT extends PostgresTestSupport {

    private static final String MAP_VERSION = "SEM-2026-09-10-001";
    private static final String SEMICONDUCTOR = "SEMICONDUCTOR";
    private static final String BATTERY = "SECONDARY_BATTERY";
    private static final String SEMICONDUCTOR_ETF = "091160.XKRX";
    private static final String BATTERY_ETF = "305720.XKRX";

    /** contracts/sector-api.md의 {@code GET /sectors} 예시에 실린 섹터 항목 그대로다. */
    private static final String CONTRACT_EXAMPLE_SECTOR =
            """
            {
              "code": "SEMICONDUCTOR",
              "name": "반도체",
              "classification": "INDUSTRY",
              "representativeEtf": {
                "instrumentId": "091160.XKRX",
                "name": "KODEX 반도체",
                "listedOn": "2006-06-27",
                "benchmarkIndex": "KRX 반도체"
              }
            }
            """;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearMap() {
        deleteSectorTables();
    }

    @AfterEach
    void restoreEmptyMap() {
        deleteSectorTables();
    }

    /**
     * 대응표 네 표를 FK 역순으로 비운다.
     *
     * <p>이 표들에는 자료 버전 쪽과 달리 UPDATE·DELETE를 거부하는 불변 트리거가 없어 지울 수 있다. 컨텍스트를 다른 테스트와 공유하므로 테스트 전후로 반드시
     * 비운다 — 특히 {@code effective_to IS NULL}인 행을 남기면 부분 UNIQUE 인덱스 때문에 다음 테스트의 INSERT가 실패한다.
     */
    private void deleteSectorTables() {
        jdbcTemplate.update(
                "DELETE FROM sector_etf_map_entry WHERE map_version_id IN"
                        + " (SELECT id FROM sector_etf_map_version WHERE version = ?)",
                MAP_VERSION);
        jdbcTemplate.update("DELETE FROM sector_etf_map_version WHERE version = ?", MAP_VERSION);
        // 공통 준비 단계(PostgresTestSupport)가 심는 씨앗을 함께 치운다. 씨앗은 같은 섹터·종목을
        // 참조하고 대응표 하나를 열어 두므로, 남겨 두면 이 검사가 섹터를 지우지도 자기 대응표를
        // 열지도 못한다. 씨앗은 다음 검사 전에 다시 심긴다.
        jdbcTemplate.update(
                "UPDATE sector_etf_map_version"
                        + " SET effective_to = effective_from + INTERVAL '1 second'"
                        + " WHERE effective_to IS NULL");
        jdbcTemplate.update(
                "DELETE FROM sector_etf_map_entry WHERE sector_id IN"
                        + " (SELECT id FROM sector WHERE code IN (?, ?))"
                        + " OR etf_id IN"
                        + " (SELECT id FROM representative_etf WHERE instrument_id IN (?, ?))",
                SEMICONDUCTOR,
                BATTERY,
                SEMICONDUCTOR_ETF,
                BATTERY_ETF);
        jdbcTemplate.update("DELETE FROM sector WHERE code IN (?, ?)", SEMICONDUCTOR, BATTERY);
        jdbcTemplate.update(
                "DELETE FROM representative_etf WHERE instrument_id IN (?, ?)",
                SEMICONDUCTOR_ETF,
                BATTERY_ETF);
    }

    /**
     * 예시 대응표 두 건을 넣는다. 하나는 계약 예시와 같은 값이고, 다른 하나는 {@code classification = THEME}이라 두 값을 모두 덮는다.
     *
     * @param openVersion 열린 버전으로 둘지 여부. false면 닫힌 버전만 남아 "발행된 대응표가 없는" 상태다
     * @return 넣은 대응표 버전 이름
     */
    private String publishSampleMap(boolean openVersion) {
        long semiconductor = insertSector(SEMICONDUCTOR, "반도체", "INDUSTRY");
        long battery = insertSector(BATTERY, "2차전지", "THEME");
        long kodexSemiconductor =
                insertEtf(SEMICONDUCTOR_ETF, "KODEX 반도체", "2006-06-27", "KRX 반도체", "삼성자산운용");
        long kodexBattery =
                insertEtf(
                        BATTERY_ETF, "KODEX 2차전지산업", "2018-09-12", "FnGuide 2차전지 산업 지수", "삼성자산운용");

        Long mapVersionId =
                jdbcTemplate.queryForObject(
                        """
                        INSERT INTO sector_etf_map_version
                            (version, effective_from, effective_to, is_sample, published_at)
                        VALUES (?, TIMESTAMPTZ '2026-09-10 00:00:00+09',
                                CAST(? AS TIMESTAMPTZ), TRUE,
                                TIMESTAMPTZ '2026-09-10 00:00:00+09')
                        RETURNING id
                        """,
                        Long.class,
                        MAP_VERSION,
                        // NULL이면 열린 버전이다. CAST를 붙이는 이유는 PostgreSQL이 파라미터가 NULL일 때
                        // 자리의 타입을 추론하지 못해 그대로 두면 준비 단계에서 거절하기 때문이다.
                        openVersion ? null : "2026-09-11 00:00:00+09");

        // 넣는 차례를 기대하는 차례와 일부러 반대로 둔다. 코드 오름차순은 SECONDARY_BATTERY가
        // 앞이므로('C' < 'M') 넣기는 SEMICONDUCTOR부터 한다. 같은 차례로 넣으면
        // sectorsAreOrderedByCode가 정렬을 지워도 통과해 아무것도 지키지 못한다.
        insertEntry(mapVersionId, semiconductor, kodexSemiconductor);
        insertEntry(mapVersionId, battery, kodexBattery);
        return MAP_VERSION;
    }

    private long insertSector(String code, String name, String classification) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO sector (code, name, classification) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                code,
                name,
                classification);
    }

    private long insertEtf(
            String instrumentId,
            String name,
            String listedOn,
            String benchmarkIndex,
            String manager) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO representative_etf
                    (instrument_id, name, listed_on, benchmark_index, manager)
                VALUES (?, ?, CAST(? AS DATE), ?, ?)
                RETURNING id
                """,
                Long.class,
                instrumentId,
                name,
                listedOn,
                benchmarkIndex,
                manager);
    }

    private void insertEntry(long mapVersionId, long sectorId, long etfId) {
        jdbcTemplate.update(
                "INSERT INTO sector_etf_map_entry (map_version_id, sector_id, etf_id)"
                        + " VALUES (?, ?, ?)",
                mapVersionId,
                sectorId,
                etfId);
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

    @Test
    @DisplayName("대응표가 발행되지 않았으면 빈 배열이 아니라 409 SECTOR_MAP_NOT_READY다")
    void returnsConflictWhenMapNotPublished() throws Exception {
        mockMvc.perform(get("/sectors"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("SECTOR_MAP_NOT_READY"));
    }

    @Test
    @DisplayName("닫힌 버전만 남아 있어도 409다 — 열린 버전이 있어야 발행된 것이다")
    void returnsConflictWhenOnlyClosedVersionExists() throws Exception {
        publishSampleMap(false);

        mockMvc.perform(get("/sectors"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SECTOR_MAP_NOT_READY"));
    }

    @Test
    @DisplayName("섹터 목록이 계약 예시와 같다 — mapVersion·isSample과 항목 구조")
    void listMatchesContractExample() throws Exception {
        publishSampleMap(true);

        JsonNode data = getJson("/sectors", 200).get("data");

        assertThat(data.get("mapVersion").asText()).isEqualTo(MAP_VERSION);
        assertThat(data.get("isSample").asBoolean()).isTrue();
        // 계약 예시의 섹터 항목과 필드 하나까지 같다(빠진 필드도 더 붙은 필드도 없다).
        // 코드 오름차순이라 SEMICONDUCTOR는 SECONDARY_BATTERY 다음이다('C' < 'M').
        assertThat(data.get("sectors").get(1))
                .isEqualTo(objectMapper.readTree(CONTRACT_EXAMPLE_SECTOR));
    }

    @Test
    @DisplayName("모든 항목에 대표 ETF가 있다 — 비어 있는 항목을 내보내지 않는다 (SC-001)")
    void everySectorCarriesRepresentativeEtf() throws Exception {
        publishSampleMap(true);

        JsonNode sectors = getJson("/sectors", 200).get("data").get("sectors");

        assertThat(sectors).hasSize(2);
        for (JsonNode sector : sectors) {
            assertThat(sector.get("code").asText()).isNotBlank();
            JsonNode etf = sector.get("representativeEtf");
            assertThat(etf).isNotNull();
            assertThat(etf.isNull()).isFalse();
            assertThat(etf.get("instrumentId").asText()).matches("^\\d{6}\\.XKRX$");
            assertThat(etf.get("listedOn").asText()).isNotBlank();
            assertThat(etf.get("benchmarkIndex").asText()).isNotBlank();
        }
    }

    @Test
    @DisplayName("섹터 순서는 코드 오름차순으로 고정이다 — 같은 대응표면 매번 같은 순서다")
    void sectorsAreOrderedByCode() throws Exception {
        publishSampleMap(true);

        JsonNode sectors = getJson("/sectors", 200).get("data").get("sectors");

        // 넣은 차례는 SEMICONDUCTOR → SECONDARY_BATTERY인데도 코드 오름차순으로 나온다.
        // 정렬을 지우면 힙 스캔이 넣은 차례를 돌려주므로 이 단언이 깨진다.
        assertThat(sectors.get(0).get("code").asText()).isEqualTo("SECONDARY_BATTERY");
        assertThat(sectors.get(1).get("code").asText()).isEqualTo("SEMICONDUCTOR");
    }

    @Test
    @DisplayName("classification은 INDUSTRY·THEME 두 값이다")
    void exposesBothClassifications() throws Exception {
        publishSampleMap(true);

        JsonNode sectors = getJson("/sectors", 200).get("data").get("sectors");

        assertThat(sectors.get(0).get("classification").asText()).isEqualTo("THEME");
        assertThat(sectors.get(1).get("classification").asText()).isEqualTo("INDUSTRY");
    }
}
