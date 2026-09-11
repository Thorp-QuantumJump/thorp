package com.ssafy.thorp.sector;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 001 자료 저장 스키마의 불변식을 운영과 같은 PostgreSQL에서 검증한다 (spec 001 T003).
 *
 * <p>여기서 확인하는 것은 표가 있다는 사실이 아니라 <b>거부되어야 하는 입력이 실제로 거부되는지</b>다. 불변식을 애플리케이션이 아니라 DB에 둔 이유가 쓰는 쪽이
 * 여럿(파이썬 파이프라인·백엔드·psql 직접 접속)이기 때문이므로, 검증도 DB 경계에서 해야 뜻이 있다.
 *
 * <p>skip 조건({@code @Testcontainers(disabledWithoutDocker = true)})은 {@link PostgresTestSupport}에서
 * 물려받되, <b>Spring 컨텍스트를 새로 띄우지 않고 공용 컨테이너도 쓰지 않는다.</b> Spring은 설정이 다른
 * {@code @DataJpaTest}·{@code @SpringBootTest}마다 컨텍스트를 따로 캐시하고 각 컨텍스트가 기본 10짜리 Hikari 풀을 테스트가 끝날
 * 때까지 붙들고 있는데, 이 저장소의 DB 테스트 묶음은 이미 공용 컨테이너의 {@code max_connections}(기본 100)를 거의 다 쓰고 있다. 컨텍스트를 하나
 * 더 만들면 이 클래스가 아니라 <b>나중에 뜨는 다른 DB 테스트</b>가 {@code FATAL: sorry, too many clients already}로 죽는다(풀을
 * 2로 줄여도 마찬가지였다 — 남는 커넥션이 0이다).
 *
 * <p>그래서 이 클래스는 스키마 전용 컨테이너를 따로 띄우고 커넥션을 하나만 연다. 스키마만 보는 테스트라 JPA 컨텍스트가 필요 없고, 마이그레이션은 운영과 같은
 * 경로({@code classpath:db/migration})를 Flyway API로 직접 적용한다. <b>공용 컨테이너의 커넥션 여유가 생기면(예: {@code
 * PostgresTestSupport}가 {@code max_connections}를 올리면) 이 클래스도 {@code @DataJpaTest}로 되돌리는 편이 낫다.</b>
 *
 * <p>{@link SingleConnectionDataSource}를 {@code autoCommit = false}로 두고 테스트마다 롤백한다. 발행된 자료 버전·시계열은
 * DELETE도 거부되므로 손으로 치우는 뒷정리가 아예 불가능하다.
 *
 * <p>거부를 확인하는 단언은 세이브포인트로 감싼다({@link #rejectionOf}). PostgreSQL은 트랜잭션 안에서 문 하나가 실패하면 그 트랜잭션을 통째로
 * 중단시켜 뒤따르는 문을 모두 거절한다.
 *
 * <p>Docker가 없으면 {@link PostgresTestSupport}가 통째로 skip한다. 결과의 {@code skipped}를 반드시 확인한다.
 */
class EtfMarketDataSchemaIT extends PostgresTestSupport {

    private static final String DATASET = "KRX_DAILY_20260907";
    private static final String ETF = "091160.XKRX";
    private static final long SECTOR_ID = 9001L;
    private static final long ETF_ID = 9002L;
    private static final long MAP_VERSION_ID = 9003L;
    private static final long DATASET_VERSION_ID = 9004L;

    private static final String INSERT_PRICE =
            "INSERT INTO daily_price (dataset_id, instrument_id, date, open, high, low, close,"
                    + " volume, value, adjusted_close, adjustment_factor, trading_suspended,"
                    + " price_carried_forward) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String INSERT_ACTION =
            "INSERT INTO corporate_action (dataset_id, instrument_id, ex_date, pay_date,"
                    + " event_type, amount) VALUES (?, ?, ?, ?, ?, ?)";

    private static final String INSERT_GAP =
            "INSERT INTO data_gap (dataset_id, instrument_id, start_date, end_date, reason)"
                    + " VALUES (?, ?, ?, ?, ?)";

    private static final String INSERT_SUSPENSION =
            "INSERT INTO suspension_period (dataset_id, instrument_id, start_date, end_date,"
                    + " trading_days) VALUES (?, ?, ?, ?, ?)";

    private static final String INSERT_ETF =
            "INSERT INTO representative_etf (instrument_id, name, listed_on, delisted_on,"
                    + " benchmark_index, manager, leveraged, inverse)"
                    + " VALUES (?, '테스트 ETF', DATE '2011-04-06', ?, 'KRX 반도체', '운용사', ?, ?)";

    private static final String INSERT_MAP_VERSION =
            "INSERT INTO sector_etf_map_version (version, effective_from, effective_to, is_sample,"
                    + " published_at) VALUES (?, TIMESTAMP WITH TIME ZONE '2026-09-11T00:00:00Z',"
                    + " ?, FALSE, TIMESTAMP WITH TIME ZONE '2026-09-11T00:00:00Z')";

    private static final String INSERT_RECONCILIATION =
            "INSERT INTO reconciliation_record (dataset_version_id, etf_id, target_date,"
                    + " sample_reason, field_name, queried_value, disclosed_value, cause,"
                    + " resolution, checked_at)"
                    + " VALUES (?, ?, DATE '2011-04-06', ?, 'close', '10000', '10000', ?, ?,"
                    + " NOW())";

    private static final String INSERT_DATASET_VERSION =
            "INSERT INTO dataset_version (dataset_id, data_deadline, collected_at, last_success_at,"
                    + " complete, failure_reason, is_sample, source, map_version_id)"
                    + " VALUES (?, NOW(), NOW(), NOW(), ?, ?, TRUE, 'KRX_OPEN_API', ?)";

    /** 스키마 전용 컨테이너. 이미지 태그는 운영 Compose·{@link PostgresTestSupport}와 같다. */
    private static final PostgreSQLContainer<?> SCHEMA_POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static SingleConnectionDataSource dataSource;

    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void migrateAndOpenSingleConnection() {
        SCHEMA_POSTGRES.start();
        // 운영과 같은 경로를 그대로 적용한다. Flyway 설정이 어긋나면 여기서 바로 터진다.
        Flyway.configure()
                .dataSource(
                        SCHEMA_POSTGRES.getJdbcUrl(),
                        SCHEMA_POSTGRES.getUsername(),
                        SCHEMA_POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .load()
                .migrate();

        dataSource =
                new SingleConnectionDataSource(
                        SCHEMA_POSTGRES.getJdbcUrl(),
                        SCHEMA_POSTGRES.getUsername(),
                        SCHEMA_POSTGRES.getPassword(),
                        true);
        dataSource.setAutoCommit(false);
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void closeSingleConnection() {
        if (dataSource != null) {
            dataSource.destroy();
            dataSource = null;
        }
        SCHEMA_POSTGRES.stop();
    }

    /** 테스트마다 되돌린다. 시계열과 자료 버전은 DELETE가 거부되므로 롤백 말고는 치울 방법이 없다. */
    @AfterEach
    void rollback() throws SQLException {
        dataSource.getConnection().rollback();
    }

    @BeforeEach
    void seed() {
        jdbcTemplate.update(
                "INSERT INTO sector (id, code, name, classification)"
                        + " VALUES (?, 'SEMICONDUCTOR', '반도체', 'INDUSTRY')",
                SECTOR_ID);
        jdbcTemplate.update(
                "INSERT INTO representative_etf"
                        + " (id, instrument_id, name, listed_on, benchmark_index, manager)"
                        + " VALUES (?, ?, 'TIGER 반도체', DATE '2011-04-06', 'KRX 반도체',"
                        + " '미래에셋자산운용')",
                ETF_ID,
                ETF);
        jdbcTemplate.update(
                "INSERT INTO sector_etf_map_version"
                        + " (id, version, effective_from, effective_to, is_sample, published_at)"
                        + " VALUES (?, 'SEM-2026-09-10-001',"
                        + " TIMESTAMP WITH TIME ZONE '2026-09-10T00:00:00Z', NULL, TRUE,"
                        + " TIMESTAMP WITH TIME ZONE '2026-09-10T00:00:00Z')",
                MAP_VERSION_ID);
        jdbcTemplate.update(
                "INSERT INTO sector_etf_map_entry (map_version_id, sector_id, etf_id)"
                        + " VALUES (?, ?, ?)",
                MAP_VERSION_ID,
                SECTOR_ID,
                ETF_ID);
        jdbcTemplate.update(
                "INSERT INTO dataset_version (id, dataset_id, data_deadline, collected_at,"
                        + " last_success_at, complete, is_sample, source, map_version_id)"
                        + " VALUES (?, ?, TIMESTAMP WITH TIME ZONE '2026-09-07T06:00:00Z',"
                        + " TIMESTAMP WITH TIME ZONE '2026-09-07T07:00:00Z',"
                        + " TIMESTAMP WITH TIME ZONE '2026-09-07T07:00:00Z', TRUE, TRUE,"
                        + " 'FIXED_TEST_DATA', ?)",
                DATASET_VERSION_ID,
                DATASET,
                MAP_VERSION_ID);
    }

    // -----------------------------------------------------------------------
    // 표·키·인덱스
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("표와 키")
    class Shape {

        @Test
        @DisplayName("관계형 6개와 시계열 5개, 모두 11개 표를 만든다")
        void createsElevenTables() {
            assertThat(
                            jdbcTemplate.queryForList(
                                    "SELECT LOWER(table_name) FROM information_schema.tables"
                                            + " WHERE LOWER(table_schema) = 'public'"
                                            + " AND LOWER(table_name) IN ('sector',"
                                            + " 'representative_etf', 'sector_etf_map_version',"
                                            + " 'sector_etf_map_entry', 'dataset_version',"
                                            + " 'reconciliation_record', 'daily_price',"
                                            + " 'corporate_action', 'daily_index', 'data_gap',"
                                            + " 'suspension_period')",
                                    String.class))
                    .containsExactlyInAnyOrder(
                            "sector",
                            "representative_etf",
                            "sector_etf_map_version",
                            "sector_etf_map_entry",
                            "dataset_version",
                            "reconciliation_record",
                            "daily_price",
                            "corporate_action",
                            "daily_index",
                            "data_gap",
                            "suspension_period");
        }

        @Test
        @DisplayName("daily_price의 PK는 (dataset_id, instrument_id, date)다")
        void dailyPricePrimaryKeyOrder() {
            assertThat(keyColumnsOf("pk_daily_price"))
                    .containsExactly("dataset_id", "instrument_id", "date");
        }

        @Test
        @DisplayName("004가 요구한 조회 인덱스 (instrument_id, dataset_id, date)를 함께 둔다")
        void dailyPriceLookupIndex() {
            // PK 선두가 dataset_id라 한 종목·한 자료 버전을 기간으로 훑는 조회를 처리하지 못한다.
            assertThat(indexDefinitionOf("idx_daily_price_instrument_dataset_date"))
                    .contains("(instrument_id, dataset_id, date)");
        }

        @Test
        @DisplayName("다른 시계열 표에도 같은 순서의 조회 인덱스를 둔다")
        void otherTimeSeriesLookupIndexes() {
            assertThat(indexDefinitionOf("idx_corporate_action_instrument_dataset_ex_date"))
                    .contains("(instrument_id, dataset_id, ex_date)");
            assertThat(indexDefinitionOf("idx_daily_index_code_dataset_date"))
                    .contains("(index_code, dataset_id, date)");
            assertThat(indexDefinitionOf("idx_data_gap_instrument_dataset_start"))
                    .contains("(instrument_id, dataset_id, start_date)");
            assertThat(indexDefinitionOf("idx_suspension_period_instrument_dataset_start"))
                    .contains("(instrument_id, dataset_id, start_date)");
        }

        @Test
        @DisplayName("004가 요구한 열이 001의 이름으로 들어 있다")
        void contractColumnsExist() {
            assertThat(columnsOf("daily_price"))
                    .contains(
                            "instrument_id",
                            "date",
                            "open",
                            "high",
                            "low",
                            "close",
                            "volume",
                            "value",
                            "adjusted_close",
                            "adjustment_factor",
                            "trading_suspended",
                            "price_carried_forward",
                            "dataset_id");
            assertThat(columnsOf("corporate_action")).contains("ex_date", "pay_date");
            assertThat(columnsOf("dataset_version")).contains("source", "data_deadline");
            // 지수에는 기업행위도 거래정지도 없다 (data-model.md daily_index).
            assertThat(columnsOf("daily_index"))
                    .doesNotContain(
                            "adjusted_close",
                            "adjustment_factor",
                            "trading_suspended",
                            "price_carried_forward");
        }

        @Test
        @DisplayName("거래일 달력 표를 따로 만들지 않는다 — daily_index의 날짜 집합이 달력이다")
        void noSeparateTradingCalendarTable() {
            assertThat(
                            jdbcTemplate.queryForList(
                                    "SELECT LOWER(table_name) FROM information_schema.tables"
                                            + " WHERE LOWER(table_schema) = 'public'"
                                            + " AND LOWER(table_name) IN ('trading_calendar',"
                                            + " 'trading_day', 'trading_days', 'market_calendar')",
                                    String.class))
                    .isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // 불변식 1 — (map_version_id, sector_id) UNIQUE
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("불변식 1 — 한 대응표 버전 안에서 섹터마다 대표 ETF가 하나다")
    class SectorUniquePerMapVersion {

        @Test
        @DisplayName("같은 (map_version_id, sector_id)를 두 번 넣으면 거부된다")
        void duplicateSectorInSameVersionRejected() {
            assertThat(
                            rejectionOf(
                                    "INSERT INTO sector_etf_map_entry"
                                            + " (map_version_id, sector_id, etf_id)"
                                            + " VALUES (?, ?, ?)",
                                    MAP_VERSION_ID,
                                    SECTOR_ID,
                                    ETF_ID))
                    .as("한 버전 안에서 섹터마다 대표 ETF가 정확히 하나여야 한다 (FR-001, SC-001)")
                    .isNotNull();
        }

        @Test
        @DisplayName("같은 ETF가 다른 섹터에 붙는 것은 막지 않는다")
        void sameEtfInAnotherSectorAllowed() {
            jdbcTemplate.update(
                    "INSERT INTO sector (id, code, name, classification)"
                            + " VALUES (9101, 'IT', 'IT', 'THEME')");

            assertThat(
                            rejectionOf(
                                    "INSERT INTO sector_etf_map_entry"
                                            + " (map_version_id, sector_id, etf_id)"
                                            + " VALUES (?, 9101, ?)",
                                    MAP_VERSION_ID,
                                    ETF_ID))
                    .as("구성종목 중복은 대표 상품 정보로 알린다 (FR-015). 저장을 막지 않는다")
                    .isNull();
        }

        @Test
        @DisplayName("다른 버전이면 같은 섹터를 다시 담을 수 있다")
        void sameSectorInAnotherVersionAllowed() {
            jdbcTemplate.update(
                    "UPDATE sector_etf_map_version"
                            + " SET effective_to = TIMESTAMP WITH TIME ZONE '2026-09-11T00:00:00Z'"
                            + " WHERE id = ?",
                    MAP_VERSION_ID);
            jdbcTemplate.update(INSERT_MAP_VERSION, "SEM-2026-09-11-001", null);
            Long newVersionId =
                    jdbcTemplate.queryForObject(
                            "SELECT id FROM sector_etf_map_version WHERE version = ?",
                            Long.class,
                            "SEM-2026-09-11-001");

            assertThat(
                            rejectionOf(
                                    "INSERT INTO sector_etf_map_entry"
                                            + " (map_version_id, sector_id, etf_id)"
                                            + " VALUES (?, ?, ?)",
                                    newVersionId,
                                    SECTOR_ID,
                                    ETF_ID))
                    .isNull();
        }
    }

    // -----------------------------------------------------------------------
    // 불변식 2 — leveraged·inverse true 거부
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("불변식 2 — 레버리지·인버스 ETF는 저장 자체를 거부한다")
    class LeveragedAndInverseRejected {

        @Test
        @DisplayName("leveraged = true인 행이 거부된다")
        void leveragedRejected() {
            assertThat(rejectionOf(INSERT_ETF, "122630.XKRX", null, true, false))
                    .as("국내 상장 비레버리지 ETF만 허용한다 (FR-001)")
                    .isNotNull();
        }

        @Test
        @DisplayName("inverse = true인 행이 거부된다")
        void inverseRejected() {
            assertThat(rejectionOf(INSERT_ETF, "114800.XKRX", null, false, true))
                    .as("국내 상장 비인버스 ETF만 허용한다 (FR-001)")
                    .isNotNull();
        }

        @Test
        @DisplayName("둘 다 false면 저장된다 — 원천에서 읽은 값을 남기려고 둔 열이다")
        void plainEtfAccepted() {
            assertThat(rejectionOf(INSERT_ETF, "091170.XKRX", null, false, false)).isNull();
        }

        @Test
        @DisplayName("상장폐지일이 상장일보다 앞서면 거부된다")
        void delistedBeforeListedRejected() {
            assertThat(
                            rejectionOf(
                                    INSERT_ETF,
                                    "091180.XKRX",
                                    LocalDate.of(2010, 1, 1),
                                    false,
                                    false))
                    .isNotNull();
            assertThat(
                            rejectionOf(
                                    INSERT_ETF,
                                    "091190.XKRX",
                                    LocalDate.of(2020, 1, 1),
                                    false,
                                    false))
                    .isNull();
        }

        @Test
        @DisplayName("종목 식별자가 {6자리}.XKRX 형식이 아니면 거부된다")
        void instrumentIdFormatEnforced() {
            assertThat(rejectionOf(INSERT_ETF, "091160.KS", null, false, false))
                    .as("yfinance 표기(.KS)를 그대로 넣지 못하게 한다")
                    .isNotNull();
        }
    }

    // -----------------------------------------------------------------------
    // 불변식 3 — effective_to IS NULL 행이 최대 하나
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("불변식 3 — effective_to가 NULL인 대응표 버전은 최대 하나다")
    class SingleOpenMapVersion {

        @Test
        @DisplayName("열린 버전이 이미 있으면 두 번째 열린 버전이 거부된다")
        void secondOpenVersionRejected() {
            assertThat(rejectionOf(INSERT_MAP_VERSION, "SEM-2026-09-11-001", null))
                    .as("현재 버전은 하나여야 한다 (data-model.md sector_etf_map_version 불변식)")
                    .isNotNull();
        }

        @Test
        @DisplayName("닫힌 버전은 여러 개여도 된다")
        void multipleClosedVersionsAllowed() {
            assertThat(
                            rejectionOf(
                                    INSERT_MAP_VERSION,
                                    "SEM-2026-09-11-001",
                                    LocalDate.of(2026, 9, 12).atStartOfDay()))
                    .isNull();
        }

        @Test
        @DisplayName("이전 버전을 닫으면 새 열린 버전을 만들 수 있다")
        void closingPreviousVersionAllowsNewOne() {
            // 대응표 버전에는 불변 트리거를 걸지 않는다. 새 버전을 발행하려면 이전 버전의
            // effective_to를 닫아야 하기 때문이다. 항목(sector_etf_map_entry)은 버전마다 새로 만든다.
            jdbcTemplate.update(
                    "UPDATE sector_etf_map_version"
                            + " SET effective_to = TIMESTAMP WITH TIME ZONE '2026-09-11T00:00:00Z'"
                            + " WHERE id = ?",
                    MAP_VERSION_ID);

            assertThat(rejectionOf(INSERT_MAP_VERSION, "SEM-2026-09-11-001", null)).isNull();
        }
    }

    // -----------------------------------------------------------------------
    // 불변식 4 — cause ↔ resolution 조합 제한
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("불변식 4 — 대조 결과의 cause와 resolution 조합을 제한한다")
    class ReconciliationCauseResolution {

        @Test
        @DisplayName("FR-017이 정한 네 조합만 저장된다")
        void allowedPairsAccepted() {
            assertThat(insertRecord("MATCH", "NONE")).isNull();
            assertThat(insertRecord("PIPELINE_ERROR", "REPUBLISHED")).isNull();
            assertThat(insertRecord("DEFINITION_DIFF", "RECORDED_ONLY")).isNull();
            assertThat(insertRecord("UNKNOWN", "RANGE_EXCLUDED")).isNull();
        }

        @Test
        @DisplayName("원인과 처리가 어긋난 조합이 거부된다")
        void crossedPairsRejected() {
            // 원인 불명인데 재발행하거나 일치인데 구간을 빼는 조합은 FR-017의 처리 규칙이 아니다.
            assertThat(insertRecord("UNKNOWN", "REPUBLISHED")).isNotNull();
            assertThat(insertRecord("MATCH", "RANGE_EXCLUDED")).isNotNull();
            assertThat(insertRecord("DEFINITION_DIFF", "NONE")).isNotNull();
            assertThat(insertRecord("PIPELINE_ERROR", "RECORDED_ONLY")).isNotNull();
        }

        @Test
        @DisplayName("모르는 cause·resolution 값이 거부된다")
        void unknownEnumValuesRejected() {
            assertThat(insertRecord("FIXED_BY_HAND", "NONE")).isNotNull();
            assertThat(insertRecord("MATCH", "VALUE_PATCHED")).isNotNull();
        }

        @Test
        @DisplayName("표본 사유는 상장 첫날·분배금·분할뿐이다 — 평범한 거래일은 거부된다")
        void ordinaryTradingDaySampleRejected() {
            assertThat(
                            rejectionOf(
                                    INSERT_RECONCILIATION,
                                    DATASET_VERSION_ID,
                                    ETF_ID,
                                    "ORDINARY_DAY",
                                    "MATCH",
                                    "NONE"))
                    .as("평범한 거래일은 표본에 넣지 않는다 (FR-016)")
                    .isNotNull();
        }

        private DataAccessException insertRecord(String cause, String resolution) {
            return rejectionOf(
                    INSERT_RECONCILIATION,
                    DATASET_VERSION_ID,
                    ETF_ID,
                    "LISTING",
                    cause,
                    resolution);
        }
    }

    // -----------------------------------------------------------------------
    // 불변식 5 — 발행된 자료 버전과 시계열의 UPDATE·DELETE 거부 (FR-017)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("불변식 5 — 발행된 자료 버전과 시계열은 고칠 수 없다 (FR-017)")
    class PublishedDatasetIsImmutable {

        @BeforeEach
        void seedTimeSeries() {
            jdbcTemplate.update(
                    INSERT_PRICE,
                    price(
                            LocalDate.of(2026, 9, 4),
                            "10000",
                            "10100",
                            "9900",
                            "10000",
                            1200L,
                            false));
            jdbcTemplate.update(
                    INSERT_ACTION,
                    DATASET,
                    ETF,
                    LocalDate.of(2026, 7, 31),
                    LocalDate.of(2026, 8, 5),
                    "DISTRIBUTION",
                    new BigDecimal("35.00000000"));
            jdbcTemplate.update(
                    "INSERT INTO daily_index (dataset_id, index_code, date, open, high, low,"
                            + " close, volume, value)"
                            + " VALUES (?, 'KOSPI', DATE '2026-09-04', 2700, 2720, 2690, 2710,"
                            + " 100, 200)",
                    DATASET);
            jdbcTemplate.update(
                    INSERT_GAP,
                    DATASET,
                    ETF,
                    LocalDate.of(2026, 8, 1),
                    LocalDate.of(2026, 8, 3),
                    "AMBIGUOUS_PRICE");
            jdbcTemplate.update(
                    INSERT_SUSPENSION,
                    DATASET,
                    ETF,
                    LocalDate.of(2026, 8, 10),
                    LocalDate.of(2026, 8, 12),
                    3);
        }

        @Test
        @DisplayName("quickstart 시나리오 3의 dataset_version UPDATE가 거부된다")
        void datasetVersionUpdateRejected() {
            assertThat(
                            rejectionOf(
                                    "UPDATE dataset_version SET data_deadline = NOW()"
                                            + " WHERE dataset_id = ?",
                                    DATASET))
                    .as("발행된 버전을 고치는 경로가 없어야 한다 (FR-017)")
                    .isNotNull();
        }

        @Test
        @DisplayName("dataset_version DELETE가 거부된다")
        void datasetVersionDeleteRejected() {
            assertThat(rejectionOf("DELETE FROM dataset_version WHERE dataset_id = ?", DATASET))
                    .isNotNull();
        }

        @Test
        @DisplayName("다섯 시계열 표의 UPDATE가 모두 거부된다")
        void timeSeriesUpdateRejected() {
            assertThat(
                            rejectionOf(
                                    "UPDATE daily_price SET close = 1 WHERE dataset_id = ?",
                                    DATASET))
                    .isNotNull();
            assertThat(
                            rejectionOf(
                                    "UPDATE corporate_action SET amount = 1 WHERE dataset_id = ?",
                                    DATASET))
                    .isNotNull();
            assertThat(
                            rejectionOf(
                                    "UPDATE daily_index SET close = 1 WHERE dataset_id = ?",
                                    DATASET))
                    .isNotNull();
            assertThat(
                            rejectionOf(
                                    "UPDATE data_gap SET reason = 'SUSPENDED_TOO_LONG'"
                                            + " WHERE dataset_id = ?",
                                    DATASET))
                    .isNotNull();
            assertThat(
                            rejectionOf(
                                    "UPDATE suspension_period SET trading_days = 1"
                                            + " WHERE dataset_id = ?",
                                    DATASET))
                    .isNotNull();
        }

        @Test
        @DisplayName("다섯 시계열 표의 DELETE가 모두 거부된다")
        void timeSeriesDeleteRejected() {
            assertThat(rejectionOf("DELETE FROM daily_price WHERE dataset_id = ?", DATASET))
                    .isNotNull();
            assertThat(rejectionOf("DELETE FROM corporate_action WHERE dataset_id = ?", DATASET))
                    .isNotNull();
            assertThat(rejectionOf("DELETE FROM daily_index WHERE dataset_id = ?", DATASET))
                    .isNotNull();
            assertThat(rejectionOf("DELETE FROM data_gap WHERE dataset_id = ?", DATASET))
                    .isNotNull();
            assertThat(rejectionOf("DELETE FROM suspension_period WHERE dataset_id = ?", DATASET))
                    .isNotNull();
        }

        @Test
        @DisplayName("TRUNCATE로 우회할 수 없다")
        void truncateRejected() {
            assertThat(rejectionOf("TRUNCATE TABLE daily_price")).isNotNull();
            assertThat(rejectionOf("TRUNCATE TABLE dataset_version CASCADE")).isNotNull();
        }

        @Test
        @DisplayName("적재(INSERT)는 막지 않는다 — 수집이 끝난 뒤 시계열을 채우는 정상 경로다")
        void insertStillAllowed() {
            assertThat(
                            rejectionOf(
                                    INSERT_PRICE,
                                    price(
                                            LocalDate.of(2026, 9, 7),
                                            "10000",
                                            "10100",
                                            "9900",
                                            "10050",
                                            900L,
                                            false)))
                    .isNull();
        }

        @Test
        @DisplayName("새 자료 버전을 발행하면 옛 버전 행이 그대로 남는다 (SC-004)")
        void newDatasetKeepsOldRows() {
            jdbcTemplate.update(
                    INSERT_DATASET_VERSION, "KRX_DAILY_20260908", true, null, MAP_VERSION_ID);
            jdbcTemplate.update(
                    "INSERT INTO daily_price (dataset_id, instrument_id, date, open, high, low,"
                            + " close, volume, value, adjusted_close, adjustment_factor)"
                            + " VALUES ('KRX_DAILY_20260908', ?, DATE '2026-09-04', 10000, 10100,"
                            + " 9900, 10000, 1200, 12000000, 9800.5, 0.98)",
                    ETF);

            assertThat(
                            jdbcTemplate.queryForList(
                                    "SELECT dataset_id FROM daily_price"
                                            + " WHERE instrument_id = ?"
                                            + " AND date = DATE '2026-09-04'"
                                            + " ORDER BY dataset_id",
                                    String.class,
                                    ETF))
                    .containsExactly("KRX_DAILY_20260907", "KRX_DAILY_20260908");
        }

        @Test
        @DisplayName("자료 버전 행이 없으면 시계열을 넣을 수 없다 — 적재 순서가 강제된다")
        void timeSeriesRequiresPublishedDatasetVersion() {
            assertThat(
                            rejectionOf(
                                    "INSERT INTO daily_price (dataset_id, instrument_id, date,"
                                            + " open, high, low, close, volume, value, adjusted_close,"
                                            + " adjustment_factor) VALUES ('KRX_DAILY_NOT_PUBLISHED',"
                                            + " ?, DATE '2026-09-04', 10000, 10000, 10000, 10000, 1,"
                                            + " 1, 10000, 1)",
                                    ETF))
                    .isNotNull();
        }
    }

    // -----------------------------------------------------------------------
    // 거래정지일 표현과 가격 검증 규칙
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("거래정지일 표현과 가격 검증 규칙")
    class PriceShape {

        @Test
        @DisplayName("정지일 행은 OHLC가 모두 직전 종가이고 volume·value가 0이며 carried_forward다")
        void suspendedRowAccepted() {
            assertThat(
                            rejectionOf(
                                    INSERT_PRICE,
                                    DATASET,
                                    ETF,
                                    LocalDate.of(2026, 9, 7),
                                    new BigDecimal("10000"),
                                    new BigDecimal("10000"),
                                    new BigDecimal("10000"),
                                    new BigDecimal("10000"),
                                    0L,
                                    0L,
                                    new BigDecimal("9800.5"),
                                    new BigDecimal("0.98"),
                                    true,
                                    true))
                    .as("정지는 결측이 아니라 별도 상태다. 행을 만든다")
                    .isNull();
        }

        @Test
        @DisplayName("정지일인데 거래량이 남아 있으면 거부된다")
        void suspendedRowWithVolumeRejected() {
            assertThat(
                            rejectionOf(
                                    INSERT_PRICE,
                                    DATASET,
                                    ETF,
                                    LocalDate.of(2026, 9, 7),
                                    new BigDecimal("10000"),
                                    new BigDecimal("10000"),
                                    new BigDecimal("10000"),
                                    new BigDecimal("10000"),
                                    1200L,
                                    12000000L,
                                    new BigDecimal("9800.5"),
                                    new BigDecimal("0.98"),
                                    true,
                                    true))
                    .as("정지일의 volume·value는 0이다 (contracts/market-dataset.md)")
                    .isNotNull();
        }

        @Test
        @DisplayName("정지일인데 OHLC가 서로 다르면 거부된다")
        void suspendedRowWithMovingPriceRejected() {
            assertThat(
                            rejectionOf(
                                    INSERT_PRICE,
                                    DATASET,
                                    ETF,
                                    LocalDate.of(2026, 9, 7),
                                    new BigDecimal("10000"),
                                    new BigDecimal("10100"),
                                    new BigDecimal("9900"),
                                    new BigDecimal("10000"),
                                    0L,
                                    0L,
                                    new BigDecimal("9800.5"),
                                    new BigDecimal("0.98"),
                                    true,
                                    true))
                    .as("네 값을 모두 직전 종가로 채운다")
                    .isNotNull();
        }

        @Test
        @DisplayName("정지일인데 price_carried_forward가 false면 거부된다")
        void suspendedWithoutCarriedForwardRejected() {
            assertThat(
                            rejectionOf(
                                    INSERT_PRICE,
                                    DATASET,
                                    ETF,
                                    LocalDate.of(2026, 9, 7),
                                    new BigDecimal("10000"),
                                    new BigDecimal("10000"),
                                    new BigDecimal("10000"),
                                    new BigDecimal("10000"),
                                    0L,
                                    0L,
                                    new BigDecimal("9800.5"),
                                    new BigDecimal("0.98"),
                                    true,
                                    false))
                    .isNotNull();
        }

        @Test
        @DisplayName("정지가 아닌데 직전 종가를 쓴 날은 막지 않는다 — 두 열의 뜻이 다르다")
        void carriedForwardWithoutSuspensionAllowed() {
            assertThat(
                            rejectionOf(
                                    INSERT_PRICE,
                                    DATASET,
                                    ETF,
                                    LocalDate.of(2026, 9, 7),
                                    new BigDecimal("10000"),
                                    new BigDecimal("10100"),
                                    new BigDecimal("9900"),
                                    new BigDecimal("10000"),
                                    500L,
                                    5000000L,
                                    new BigDecimal("9800.5"),
                                    new BigDecimal("0.98"),
                                    false,
                                    true))
                    .as("trading_suspended는 원인, price_carried_forward는 처리다")
                    .isNull();
        }

        @Test
        @DisplayName("high가 다른 값보다 낮으면 거부된다")
        void invalidOhlcBoundsRejected() {
            assertThat(
                            rejectionOf(
                                    INSERT_PRICE,
                                    price(
                                            LocalDate.of(2026, 9, 8),
                                            "10000",
                                            "9000",
                                            "8000",
                                            "9500",
                                            1L,
                                            false)))
                    .as("validate_prices의 high >= max(open, low, close)와 같은 조건이다")
                    .isNotNull();
        }

        @Test
        @DisplayName("OHLC를 0으로 채운 행이 거부된다")
        void zeroFilledRowRejected() {
            assertThat(
                            rejectionOf(
                                    INSERT_PRICE,
                                    price(LocalDate.of(2026, 9, 8), "0", "0", "0", "0", 0L, false)))
                    .as("결측을 0으로 채우지 않는다 (FR-005, 원칙 VI)")
                    .isNotNull();
        }

        @Test
        @DisplayName("음수 거래량이 거부된다")
        void negativeVolumeRejected() {
            assertThat(
                            rejectionOf(
                                    INSERT_PRICE,
                                    price(
                                            LocalDate.of(2026, 9, 8),
                                            "10000",
                                            "10000",
                                            "10000",
                                            "10000",
                                            -1L,
                                            false)))
                    .isNotNull();
        }

        @Test
        @DisplayName("같은 (dataset_id, instrument_id, date)를 두 번 넣으면 거부된다")
        void duplicateRowRejected() {
            jdbcTemplate.update(
                    INSERT_PRICE,
                    price(
                            LocalDate.of(2026, 9, 8),
                            "10000",
                            "10000",
                            "10000",
                            "10000",
                            1200L,
                            false));

            assertThat(
                            rejectionOf(
                                    INSERT_PRICE,
                                    price(
                                            LocalDate.of(2026, 9, 8),
                                            "10500",
                                            "10500",
                                            "10500",
                                            "10500",
                                            1300L,
                                            false)))
                    .as("004 불변 조건 1: 같은 키에 행이 하나다")
                    .isNotNull();
        }

        @Test
        @DisplayName("가격 행의 종목 식별자도 {6자리}.XKRX 형식이어야 한다")
        void instrumentIdFormatEnforced() {
            assertThat(
                            rejectionOf(
                                    INSERT_PRICE,
                                    DATASET,
                                    "091160.KS",
                                    LocalDate.of(2026, 9, 8),
                                    new BigDecimal("10000"),
                                    new BigDecimal("10000"),
                                    new BigDecimal("10000"),
                                    new BigDecimal("10000"),
                                    1L,
                                    1L,
                                    new BigDecimal("10000"),
                                    new BigDecimal("1"),
                                    false,
                                    false))
                    .isNotNull();
        }
    }

    // -----------------------------------------------------------------------
    // 기업행위
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("기업행위의 배당락일·지급일 규칙")
    class CorporateActionDates {

        @Test
        @DisplayName("분배금은 지급일이 있어야 하고 배당락일 이후여야 한다")
        void distributionRequiresPayDate() {
            assertThat(insertAction("DISTRIBUTION", null))
                    .as("지급일은 현금이 계좌에 들어오는 날이다 (004 계약)")
                    .isNotNull();
            assertThat(insertAction("DISTRIBUTION", LocalDate.of(2026, 7, 30)))
                    .as("지급일이 배당락일보다 앞설 수 없다")
                    .isNotNull();
            assertThat(insertAction("DISTRIBUTION", LocalDate.of(2026, 8, 5))).isNull();
        }

        @Test
        @DisplayName("분할은 지급일이 없어야 한다")
        void splitHasNoPayDate() {
            assertThat(insertAction("SPLIT", LocalDate.of(2026, 8, 5)))
                    .as("분할에는 지급일이 없다. amount는 비율이다")
                    .isNotNull();
            assertThat(insertAction("SPLIT", null)).isNull();
        }

        @Test
        @DisplayName("모르는 event_type이 거부된다")
        void unknownEventTypeRejected() {
            assertThat(
                            rejectionOf(
                                    INSERT_ACTION,
                                    DATASET,
                                    ETF,
                                    LocalDate.of(2026, 7, 31),
                                    null,
                                    "MERGER",
                                    new BigDecimal("1.0")))
                    .isNotNull();
        }

        @Test
        @DisplayName("같은 배당락일에 분배금과 분할이 함께 있을 수 있다 — event_type이 키에 들어간다")
        void bothEventTypesOnSameExDate() {
            jdbcTemplate.update(
                    INSERT_ACTION,
                    DATASET,
                    ETF,
                    LocalDate.of(2026, 7, 31),
                    LocalDate.of(2026, 8, 5),
                    "DISTRIBUTION",
                    new BigDecimal("35.0"));

            assertThat(insertAction("SPLIT", null)).isNull();
        }

        private DataAccessException insertAction(String eventType, LocalDate payDate) {
            return rejectionOf(
                    INSERT_ACTION,
                    DATASET,
                    ETF,
                    LocalDate.of(2026, 7, 31),
                    payDate,
                    eventType,
                    new BigDecimal("35.0"));
        }
    }

    // -----------------------------------------------------------------------
    // 자료 부족 구간·정지 구간·자료 버전
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("자료 부족 구간과 정지 구간")
    class GapAndSuspension {

        @Test
        @DisplayName("모르는 자료 부족 사유가 거부된다")
        void unknownGapReasonRejected() {
            assertThat(insertGap("HOLIDAY", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 3)))
                    .isNotNull();
            assertThat(
                            insertGap(
                                    "AMBIGUOUS_PRICE",
                                    LocalDate.of(2026, 8, 1),
                                    LocalDate.of(2026, 8, 3)))
                    .isNull();
            // SUSPENDED_TOO_LONG은 PD-19·PD-20 확정 뒤에 쓰는 값이며 스키마는 미리 허용한다.
            assertThat(
                            insertGap(
                                    "SUSPENDED_TOO_LONG",
                                    LocalDate.of(2026, 8, 5),
                                    LocalDate.of(2026, 8, 7)))
                    .isNull();
        }

        @Test
        @DisplayName("끝나는 날이 시작일보다 앞서면 거부된다")
        void reversedRangeRejected() {
            assertThat(
                            insertGap(
                                    "AMBIGUOUS_PRICE",
                                    LocalDate.of(2026, 8, 3),
                                    LocalDate.of(2026, 8, 1)))
                    .isNotNull();
            assertThat(
                            rejectionOf(
                                    INSERT_SUSPENSION,
                                    DATASET,
                                    ETF,
                                    LocalDate.of(2026, 8, 12),
                                    LocalDate.of(2026, 8, 10),
                                    3))
                    .isNotNull();
        }

        @Test
        @DisplayName("거래일 수가 0 이하인 정지 구간이 거부된다")
        void nonPositiveTradingDaysRejected() {
            assertThat(
                            rejectionOf(
                                    INSERT_SUSPENSION,
                                    DATASET,
                                    ETF,
                                    LocalDate.of(2026, 8, 10),
                                    LocalDate.of(2026, 8, 12),
                                    0))
                    .isNotNull();
        }

        private DataAccessException insertGap(String reason, LocalDate start, LocalDate end) {
            return rejectionOf(INSERT_GAP, DATASET, ETF, start, end, reason);
        }
    }

    @Nested
    @DisplayName("자료 버전 기록 규칙")
    class DatasetVersionRules {

        @Test
        @DisplayName("부분 갱신은 실패 사유 없이 저장할 수 없다 (FR-014)")
        void incompleteWithoutReasonRejected() {
            assertThat(insertVersion("KRX_DAILY_20260909", false, null))
                    .as("부분만 갱신된 자료를 완전한 자료로 표시하지 않는다")
                    .isNotNull();
            assertThat(insertVersion("KRX_DAILY_20260909", false, "KRX 이용 한도 도달")).isNull();
        }

        @Test
        @DisplayName("같은 dataset_id를 두 번 발행할 수 없다")
        void duplicateDatasetIdRejected() {
            assertThat(insertVersion(DATASET, true, null)).isNotNull();
        }

        @Test
        @DisplayName("대응표 버전을 가리키지 않는 자료 버전이 거부된다")
        void datasetVersionRequiresMapVersion() {
            assertThat(
                            rejectionOf(
                                    INSERT_DATASET_VERSION,
                                    "KRX_DAILY_20260910",
                                    true,
                                    null,
                                    987654L))
                    .isNotNull();
        }

        private DataAccessException insertVersion(
                String datasetId, boolean complete, String failureReason) {
            return rejectionOf(
                    INSERT_DATASET_VERSION, datasetId, complete, failureReason, MAP_VERSION_ID);
        }
    }

    // -----------------------------------------------------------------------
    // 도우미
    // -----------------------------------------------------------------------

    /** {@link #INSERT_PRICE}의 인자를 만든다. value는 거래량 × 가격의 대략값이며 검증 대상이 아니다. */
    private static Object[] price(
            LocalDate date,
            String open,
            String high,
            String low,
            String close,
            long volume,
            boolean suspended) {
        return new Object[] {
            DATASET,
            ETF,
            date,
            new BigDecimal(open),
            new BigDecimal(high),
            new BigDecimal(low),
            new BigDecimal(close),
            volume,
            Math.max(volume, 0L) * 10L,
            new BigDecimal("9800.5"),
            new BigDecimal("0.98"),
            suspended,
            suspended
        };
    }

    /**
     * 문 하나를 실행하고 거부되면 그 예외를, 통과하면 {@code null}을 돌려준다.
     *
     * <p>세이브포인트로 감싸는 이유: PostgreSQL은 트랜잭션 안에서 문이 하나 실패하면 트랜잭션을 중단 상태로 만들어 뒤따르는 문을 모두 거절한다. 되돌리지 않으면
     * 한 테스트 안에서 거부를 두 번 확인할 수 없다. 성공한 문도 함께 되돌리므로, 뒤의 단언이 앞에서 넣은 행에 영향받지 않는다. 앞선 행이 필요한 경우에는 {@code
     * jdbcTemplate.update}로 직접 넣는다.
     */
    private DataAccessException rejectionOf(String sql, Object... args) {
        jdbcTemplate.execute("SAVEPOINT invariant_probe");
        try {
            jdbcTemplate.update(sql, args);
            return null;
        } catch (DataAccessException rejected) {
            return rejected;
        } finally {
            jdbcTemplate.execute("ROLLBACK TO SAVEPOINT invariant_probe");
        }
    }

    private List<String> columnsOf(String table) {
        return jdbcTemplate.queryForList(
                "SELECT LOWER(column_name) FROM information_schema.columns"
                        + " WHERE LOWER(table_schema) = 'public' AND LOWER(table_name) = ?",
                String.class,
                table);
    }

    private List<String> keyColumnsOf(String constraintName) {
        return jdbcTemplate.queryForList(
                "SELECT LOWER(column_name) FROM information_schema.key_column_usage"
                        + " WHERE LOWER(table_schema) = 'public'"
                        + " AND LOWER(constraint_name) = ?"
                        + " ORDER BY ordinal_position",
                String.class,
                constraintName);
    }

    /** 인덱스 정의를 돌려준다. {@code date}처럼 키워드인 열 이름은 따옴표가 붙을 수 있어 떼고 비교한다. */
    private String indexDefinitionOf(String indexName) {
        String definition =
                jdbcTemplate.queryForObject(
                        "SELECT indexdef FROM pg_indexes"
                                + " WHERE schemaname = 'public' AND indexname = ?",
                        String.class,
                        indexName);
        return definition == null ? "" : definition.replace("\"", "");
    }
}
