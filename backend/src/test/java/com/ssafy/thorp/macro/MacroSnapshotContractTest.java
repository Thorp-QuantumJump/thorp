package com.ssafy.thorp.macro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.common.config.JpaAuditingConfig;
import com.ssafy.thorp.macro.application.exception.MacroRegistryNotPublishedException;
import com.ssafy.thorp.macro.application.result.MacroSnapshotResult;
import com.ssafy.thorp.macro.application.service.MacroSnapshotQueryService;
import com.ssafy.thorp.macro.infrastructure.persistence.MacroSnapshotRepositoryImpl;
import com.ssafy.thorp.macro.presentation.response.MacroSnapshotResponse;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 008 조회 계약을 고정한다 (tasks.md T034, SC-008).
 *
 * <p>스프링 배선 없이 저장소와 서비스를 직접 엮는다. 이 조회는 Entity 가 없고 {@link JdbcTemplate} 하나만 쓰므로 컨텍스트를 더 얹어도 검증되는 것이
 * 늘지 않는다.
 *
 * <p>확인하는 것은 <b>빈 지표가 0개</b>라는 한 가지다(SC-008). 17개가 오는지만 보면 이름이나 단위가 비어도 통과하고, 그 화면은 값만 있고 그것이 무엇인지
 * 말하지 못한다.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
@DisplayName("거시지표 스냅샷 조회 계약")
class MacroSnapshotContractTest extends PostgresTestSupport {

    /** 분야별 지표 수 — 등록표의 실제 분포다(FR-001). 합이 17이다. */
    private static final Map<String, Integer> FIELD_SIZES =
            Map.of(
                    "production", 3,
                    "consumption_investment", 3,
                    "external_trade", 3,
                    "labor", 2,
                    "prices_commodities", 2,
                    "finance_housing", 3,
                    "overseas", 1);

    private static final List<String> FIELD_ORDER =
            List.of(
                    "production",
                    "consumption_investment",
                    "external_trade",
                    "labor",
                    "prices_commodities",
                    "finance_housing",
                    "overseas");

    @Autowired private JdbcTemplate jdbc;

    private MacroSnapshotQueryService service;

    @BeforeEach
    void setUp() {
        service =
                new MacroSnapshotQueryService(
                        new MacroSnapshotRepositoryImpl(jdbc, new ObjectMapper()));
    }

    @Test
    @DisplayName("등록표가 한 번도 발행되지 않았으면 조회가 실패한다 — 자료 없음과 다르다")
    void failsWhenRegistryNeverPublished() {
        assertThatThrownBy(() -> service.getSnapshot())
                .isInstanceOf(MacroRegistryNotPublishedException.class);
    }

    @Test
    @DisplayName("17개 등록 정보가 모두 오고 값·단위·대상월·변환·출처·작성기관이 빈 지표가 0개다")
    void everyIndicatorCarriesItsRegistration() {
        seedRegistry();
        seedPublishedDataset();

        MacroSnapshotResult snapshot = service.getSnapshot();

        assertThat(snapshot.indicators()).hasSize(17);
        assertThat(snapshot.fields()).hasSize(7);
        for (MacroSnapshotResult.IndicatorSummary indicator : snapshot.indicators()) {
            assertThat(indicator.displayName()).as(indicator.indicatorId()).isNotBlank();
            assertThat(indicator.source()).as(indicator.indicatorId()).isNotBlank();
            assertThat(indicator.agency()).as(indicator.indicatorId()).isNotBlank();
            assertThat(indicator.rawUnit()).as(indicator.indicatorId()).isNotBlank();
            assertThat(indicator.transformedUnit()).as(indicator.indicatorId()).isNotBlank();
            assertThat(indicator.transform()).as(indicator.indicatorId()).isNotBlank();
            assertThat(indicator.referenceMonth()).as(indicator.indicatorId()).isNotNull();
            assertThat(indicator.latest()).as(indicator.indicatorId()).isNotNull();
            assertThat(indicator.status()).as(indicator.indicatorId()).isEqualTo("READY");
        }
    }

    @Test
    @DisplayName("작성기관이 제공기관과 다른 지표를 구분해 내보낸다")
    void separatesAgencyFromProvider() {
        seedRegistry();
        seedPublishedDataset();

        Map<String, MacroSnapshotResult.IndicatorSummary> byId =
                service.getSnapshot().indicators().stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        MacroSnapshotResult.IndicatorSummary::indicatorId, i -> i));

        assertThat(byId.get("prices_commodities_2").agency()).isEqualTo("국제통화기금(IMF)");
        assertThat(byId.get("prices_commodities_2").source()).contains("한국은행");
    }

    @Test
    @DisplayName("경로는 세 달을 유지하고 빠진 달은 사유와 함께 남는다")
    void pathKeepsMissingMonths() {
        seedRegistry();
        seedPublishedDataset();
        // 한 지표의 가운데 달을 결측으로 바꾼다.
        jdbc.update(
                """
                UPDATE macro_observation SET transformed_value = NULL, missing_reason = 'SOURCE_NOT_RELEASED'
                WHERE indicator_id = 'production_1' AND target_month = '2026-06-01'::date
                """);

        MacroSnapshotResult.IndicatorSummary indicator =
                service.getSnapshot().indicators().stream()
                        .filter(i -> i.indicatorId().equals("production_1"))
                        .findFirst()
                        .orElseThrow();

        assertThat(indicator.path()).hasSize(3);
        assertThat(indicator.path().get(1).transformedValue()).isNull();
        assertThat(indicator.path().get(1).missingReason()).isEqualTo("NOT_RELEASED");
        assertThat(indicator.path().get(2).transformedValue()).isNotNull();
    }

    @Test
    @DisplayName("수집이 실패한 지표는 값이 남아 있어도 실패 상태로 나간다")
    void marksIndicatorsWhoseCollectionFailed() {
        seedRegistry();
        seedPublishedDataset();
        seedFailedCollection("production_2");

        MacroSnapshotResult snapshot = service.getSnapshot();

        assertThat(snapshot.collection().failedIndicatorIds()).containsExactly("production_2");
        assertThat(statusOf(snapshot, "production_2")).isEqualTo("COLLECTION_FAILED");
        // 어제 발행한 자료는 그대로 있다. 실패는 자료를 지우지 않는다(FR-011).
        assertThat(valueOf(snapshot, "production_2")).isNotNull();
        assertThat(statusOf(snapshot, "production_1")).isEqualTo("READY");
    }

    @Test
    @DisplayName("예시 자료는 값이 멀쩡해도 모든 지표가 예시로 나간다")
    void marksEveryIndicatorAsExampleData() {
        seedRegistry();
        seedPublishedDataset();
        jdbc.update("UPDATE macro_dataset_version SET is_sample_data = TRUE");

        MacroSnapshotResult snapshot = service.getSnapshot();

        assertThat(snapshot.exampleData()).isTrue();
        assertThat(snapshot.indicators()).allMatch(i -> "EXAMPLE".equals(i.status()));
    }

    @Test
    @DisplayName("예시 표시가 수집 실패보다 앞선다 — 개발 대역이 운영처럼 보이지 않는다")
    void exampleDataWinsOverCollectionFailure() {
        seedRegistry();
        seedPublishedDataset();
        seedFailedCollection("production_2");
        jdbc.update("UPDATE macro_dataset_version SET is_sample_data = TRUE");

        assertThat(statusOf(service.getSnapshot(), "production_2")).isEqualTo("EXAMPLE");
    }

    @Test
    @DisplayName("완결 자료 버전이 없어도 17개 등록 정보와 자료 없음 상태를 내보낸다")
    void servesRegistrationsWithoutDataset() {
        seedRegistry();

        MacroSnapshotResult snapshot = service.getSnapshot();

        assertThat(snapshot.dataVersion()).isNull();
        assertThat(snapshot.overallReferenceMonth()).isNull();
        assertThat(snapshot.indicators()).hasSize(17);
        assertThat(snapshot.indicators()).allMatch(i -> "NO_DATA".equals(i.status()));
        assertThat(snapshot.indicators()).allMatch(i -> i.path().isEmpty());
        assertThat(snapshot.collection().status()).isEqualTo("NEVER_COLLECTED");
    }

    @Test
    @DisplayName("응답 봉투의 역사 비교 문구는 계약이 정한 문장 그대로다")
    void carriesHistoricalComparisonNotice() {
        seedRegistry();

        MacroSnapshotResponse response = MacroSnapshotResponse.from(service.getSnapshot());

        assertThat(response.historicalComparisonNotice())
                .isEqualTo("최신 개정자료를 이용한 역사 비교입니다. 당시 발표값을 재현한 값이 아닙니다.");
        assertThat(response.overallReferenceMonth()).isNull();
    }

    // ------------------------------------------------------------------
    // 준비
    // ------------------------------------------------------------------

    private void seedRegistry() {
        jdbc.update(
                """
                INSERT INTO macro_calculation_registry_version (
                    version_key, value_rule_fingerprint, change_reason, status, created_at)
                VALUES ('REG-1', repeat('a', 64), '최초 등록', 'PUBLISHED', now())
                """);
        jdbc.update(
                """
                INSERT INTO macro_display_version (version_key, change_reason, created_at)
                VALUES ('DSP-1', '최초', now())
                """);
        long registryId = idOf("macro_calculation_registry_version", "REG-1");
        long displayId = idOf("macro_display_version", "DSP-1");

        int order = 0;
        int fieldOrder = 0;
        for (String fieldId : FIELD_ORDER) {
            fieldOrder++;
            jdbc.update(
                    """
                    INSERT INTO macro_display_field (
                        display_version_id, field_id, display_name, display_order, description)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    displayId,
                    fieldId,
                    "분야 " + fieldOrder,
                    fieldOrder,
                    fieldId + " 설명");
            for (int n = 1; n <= FIELD_SIZES.get(fieldId); n++) {
                order++;
                String indicatorId = fieldId + "_" + n;
                boolean foreignAgency = indicatorId.equals("prices_commodities_2");
                jdbc.update(
                        """
                        INSERT INTO macro_indicator_definition (
                            registry_version_id, indicator_id, field_id, primary_source,
                            raw_unit, transformed_unit, transform_rule, agency, attribution,
                            license_status, license_review_status, refresh_months_n,
                            nominal_weight, revision_history_available)
                        VALUES (?, ?, ?, 'kosis', '2020=100', '%', 'YOY_GROWTH', ?, ?,
                                'ALLOWED', 'VERIFIED', 3, '"0.0476"'::jsonb, true)
                        """,
                        registryId,
                        indicatorId,
                        fieldId,
                        foreignAgency ? "국제통화기금(IMF)" : "통계청",
                        foreignAgency ? "한국은행 경제통계시스템(ECOS)" : "국가통계포털(KOSIS)");
                jdbc.update(
                        """
                        INSERT INTO macro_display_indicator (
                            display_version_id, indicator_id, display_name, display_order)
                        VALUES (?, ?, ?, ?)
                        """,
                        displayId,
                        indicatorId,
                        "지표 " + order,
                        order);
            }
        }
        jdbc.update(
                """
                INSERT INTO macro_current_publication (
                    dataset_version_id, registry_version_id, display_version_id, switched_at)
                VALUES (NULL, ?, ?, now())
                """,
                registryId,
                displayId);
    }

    private void seedPublishedDataset() {
        long registryId = idOf("macro_calculation_registry_version", "REG-1");
        jdbc.update(
                """
                INSERT INTO macro_dataset_version (
                    version_key, processing_registry_version_id, value_rule_fingerprint,
                    status, complete_indicator_count, published_at, created_at)
                VALUES ('DS-1', ?, repeat('a', 64), 'PUBLISHED', 17, now(), now())
                """,
                registryId);
        long datasetId = idOf("macro_dataset_version", "DS-1");

        for (String fieldId : FIELD_ORDER) {
            for (int n = 1; n <= FIELD_SIZES.get(fieldId); n++) {
                String indicatorId = fieldId + "_" + n;
                for (String month : List.of("2026-05-01", "2026-06-01", "2026-07-01")) {
                    jdbc.update(
                            """
                            INSERT INTO macro_observation (
                                dataset_version_id, indicator_id, target_month,
                                raw_value, transformed_value, released_at, received_at)
                            VALUES (?, ?, ?::date, '"101.2"'::jsonb, '"1.4"'::jsonb, now(), now())
                            """,
                            datasetId,
                            indicatorId,
                            month);
                }
            }
            jdbc.update(
                    """
                    INSERT INTO macro_baseline (
                        dataset_version_id, registry_version_id, scope_id,
                        reference_month, reference_month_held_back_by, computed_at)
                    VALUES (?, ?, ?, '2026-07-01'::date, '[]'::jsonb, now())
                    """,
                    datasetId,
                    registryId,
                    fieldId);
        }
        jdbc.update(
                """
                INSERT INTO macro_baseline (
                    dataset_version_id, registry_version_id, scope_id,
                    reference_month, reference_month_held_back_by, computed_at)
                VALUES (?, ?, 'ALL', '2026-07-01'::date, '[]'::jsonb, now())
                """,
                datasetId,
                registryId);
        jdbc.update("UPDATE macro_current_publication SET dataset_version_id = ?", datasetId);
    }

    private void seedFailedCollection(String failedIndicatorId) {
        jdbc.update(
                """
                INSERT INTO macro_collection_attempt (attempt_key, mode, started_at, status)
                VALUES ('ATT-1', 'REGULAR', now(), 'PARTIAL')
                """);
        Long attemptId =
                jdbc.queryForObject(
                        "SELECT id FROM macro_collection_attempt WHERE attempt_key = 'ATT-1'",
                        Long.class);
        jdbc.update(
                """
                INSERT INTO macro_collection_item_status (
                    attempt_id, indicator_id, source, status, last_error_at, error_class)
                VALUES (?, ?, 'kosis', 'FAILED', now(), 'ReadTimeout')
                """,
                attemptId,
                failedIndicatorId);
        jdbc.update(
                """
                INSERT INTO macro_collection_item_status (
                    attempt_id, indicator_id, source, status, last_success_at)
                VALUES (?, 'production_1', 'kosis', 'SUCCEEDED', now())
                """,
                attemptId);
    }

    private String statusOf(MacroSnapshotResult snapshot, String indicatorId) {
        return snapshot.indicators().stream()
                .filter(i -> i.indicatorId().equals(indicatorId))
                .findFirst()
                .orElseThrow()
                .status();
    }

    private MacroSnapshotResult.ObservationValue valueOf(
            MacroSnapshotResult snapshot, String indicatorId) {
        return snapshot.indicators().stream()
                .filter(i -> i.indicatorId().equals(indicatorId))
                .findFirst()
                .orElseThrow()
                .latest();
    }

    private long idOf(String table, String versionKey) {
        return jdbc.queryForObject(
                "SELECT id FROM " + table + " WHERE version_key = ?", Long.class, versionKey);
    }
}
