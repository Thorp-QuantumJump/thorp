package com.ssafy.thorp.macro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.support.RepositoryTestSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 거시지표 등록·수집 스키마를 운영과 같은 조건으로 본다 (tasks.md T016).
 *
 * <p>이 표들의 값을 넣는 것은 {@code data/src/data_pipeline/macro/} 배치다. Backend 에는 아직 Entity 가 없으므로 여기서 보는
 * 것은 매핑이 아니라 <b>제약이 실제로 막는지</b>다 — 배치가 들고 있는 정의와 이 스키마가 갈리면 적재는 운영에서만 깨지고, 그때 원인은 이 MR 이 아니라 배치 쪽으로
 * 보인다.
 *
 * <p>막아야 할 것을 골라 넣었다. 셋 다 "값을 지어내지 않는다"(원칙 VI)가 스키마에서 지켜지는지를 본다.
 *
 * <ul>
 *   <li>사유 없는 결측 — 왜 비었는지 없이 값만 비면 결측인지 미처리인지 구분되지 않는다
 *   <li>17개가 안 찬 공개 — 반쯤 채운 버전이 완결본으로 보인다
 *   <li>사유 없는 권리 중단 — 지표가 왜 빠졌는지 되돌릴 근거가 사라진다
 * </ul>
 */
@DisplayName("거시지표 등록·수집 마이그레이션")
class MacroSchemaIntegrationTest extends RepositoryTestSupport {

    @Autowired private JdbcTemplate jdbc;

    private long insertRegistryVersion(String key) {
        jdbc.update(
                """
                INSERT INTO macro_calculation_registry_version (
                    version_key, value_rule_fingerprint, change_reason, status, created_at)
                VALUES (?, repeat('a', 64), '최초 등록', 'PUBLISHED', now())
                """,
                key);
        return jdbc.queryForObject(
                "SELECT id FROM macro_calculation_registry_version WHERE version_key = ?",
                Long.class,
                key);
    }

    private long insertDatasetVersion(String key, String status, int completeCount) {
        long registryId = insertRegistryVersion("REG-" + key);
        jdbc.update(
                """
                INSERT INTO macro_dataset_version (
                    version_key, processing_registry_version_id, value_rule_fingerprint,
                    status, complete_indicator_count, published_at, created_at)
                VALUES (?, ?, repeat('a', 64), ?, ?,
                        CASE WHEN ? = 'PUBLISHED' THEN now() ELSE NULL END, now())
                """,
                key,
                registryId,
                status,
                completeCount,
                status);
        return jdbc.queryForObject(
                "SELECT id FROM macro_dataset_version WHERE version_key = ?", Long.class, key);
    }

    @Test
    @DisplayName("표 열둘이 마이그레이션으로 만들어진다")
    void createsAllTables() {
        List<String> tables =
                jdbc.queryForList(
                        """
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = 'public' AND table_name LIKE 'macro\\_%'
                        ORDER BY table_name
                        """,
                        String.class);

        assertThat(tables)
                .containsExactly(
                        "macro_baseline",
                        "macro_calculation_registry_version",
                        "macro_collection_attempt",
                        "macro_collection_item_status",
                        "macro_current_publication",
                        "macro_dataset_version",
                        "macro_display_field",
                        "macro_display_indicator",
                        "macro_display_version",
                        "macro_indicator_definition",
                        "macro_observation",
                        "macro_observation_lineage",
                        "macro_raw_revision");
    }

    @Test
    @DisplayName("변환값이 비었는데 사유가 없으면 넣지 못한다")
    void rejectsMissingValueWithoutReason() {
        long datasetId = insertDatasetVersion("DS-missing", "NORMALIZING", 0);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                                        INSERT INTO macro_observation (
                                            dataset_version_id, indicator_id, target_month,
                                            raw_value, transformed_value, received_at)
                                        VALUES (?, 'industrial_production', '2026-01-01'::date,
                                                '"101.2"'::jsonb, NULL, now())
                                        """,
                                        datasetId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("변환값이 있는데 결측 사유가 붙어 있으면 넣지 못한다")
    void rejectsValueWithMissingReason() {
        long datasetId = insertDatasetVersion("DS-both", "NORMALIZING", 0);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                                        INSERT INTO macro_observation (
                                            dataset_version_id, indicator_id, target_month,
                                            transformed_value, missing_reason, received_at)
                                        VALUES (?, 'industrial_production', '2026-01-01'::date,
                                                '"1.4"'::jsonb, 'BASE_MONTH_MISSING', now())
                                        """,
                                        datasetId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("결측은 사유와 함께면 들어간다")
    void acceptsMissingWithReason() {
        long datasetId = insertDatasetVersion("DS-ok", "NORMALIZING", 0);

        jdbc.update(
                """
                INSERT INTO macro_observation (
                    dataset_version_id, indicator_id, target_month,
                    raw_value, transformed_value, missing_reason, received_at)
                VALUES (?, 'industrial_production', '2026-01-01'::date,
                        '"101.2"'::jsonb, NULL, 'BEFORE_SERIES_START', now())
                """,
                datasetId);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT missing_reason FROM macro_observation WHERE dataset_version_id = ?",
                                String.class,
                                datasetId))
                .isEqualTo("BEFORE_SERIES_START");
    }

    @Test
    @DisplayName("17개가 차지 않은 자료 버전은 공개 상태가 되지 못한다")
    void rejectsPublishedWithoutAllIndicators() {
        assertThatThrownBy(() -> insertDatasetVersion("DS-partial", "PUBLISHED", 16))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("권리를 중단하면서 사유와 시점을 비우면 넣지 못한다")
    void rejectsSuspensionWithoutReason() {
        long registryId = insertRegistryVersion("REG-suspend");

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                                        INSERT INTO macro_indicator_definition (
                                            registry_version_id, indicator_id, field_id, primary_source,
                                            raw_unit, transformed_unit, transform_rule, agency, attribution,
                                            license_status, license_review_status, refresh_months_n,
                                            nominal_weight, revision_history_available)
                                        VALUES (?, 'dubai_crude', 'prices_commodities', 'ecos',
                                                'USD/bbl', 'USD/bbl', 'MONTH_AVERAGE', '국제통화기금', '한국은행',
                                                'SUSPENDED', 'PENDING_RIGHTS', 3, '"0.0588"'::jsonb, false)
                                        """,
                                        registryId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("공통 대상월이 없어도 기준월 행은 남고 월만 비운다")
    void keepsBaselineRowWhenIntersectionIsEmpty() {
        long datasetId = insertDatasetVersion("DS-baseline", "NORMALIZING", 0);
        long registryId =
                jdbc.queryForObject(
                        "SELECT processing_registry_version_id FROM macro_dataset_version WHERE id = ?",
                        Long.class,
                        datasetId);

        jdbc.update(
                """
                INSERT INTO macro_baseline (
                    dataset_version_id, registry_version_id, scope_id,
                    reference_month, reference_month_held_back_by, computed_at)
                VALUES (?, ?, 'ALL', NULL, '["overseas_cli"]'::jsonb, now())
                """,
                datasetId,
                registryId);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM macro_baseline WHERE dataset_version_id = ?"
                                        + " AND reference_month IS NULL",
                                Integer.class,
                                datasetId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("현재 포인터는 한 행을 넘지 못한다")
    void currentPublicationIsSingleton() {
        long registryId = insertRegistryVersion("REG-pointer");
        jdbc.update(
                """
                INSERT INTO macro_display_version (version_key, change_reason, created_at)
                VALUES ('DSP-1', '최초', now())
                """);
        long displayId =
                jdbc.queryForObject(
                        "SELECT id FROM macro_display_version WHERE version_key = 'DSP-1'",
                        Long.class);

        jdbc.update(
                """
                INSERT INTO macro_current_publication (
                    dataset_version_id, registry_version_id, display_version_id, switched_at)
                VALUES (NULL, ?, ?, now())
                """,
                registryId,
                displayId);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                                        INSERT INTO macro_current_publication (
                                            dataset_version_id, registry_version_id,
                                            display_version_id, switched_at)
                                        VALUES (NULL, ?, ?, now())
                                        """,
                                        registryId,
                                        displayId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
