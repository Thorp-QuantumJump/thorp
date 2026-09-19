package com.ssafy.thorp.macro;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.support.RepositoryTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 같은 자료·계산 등록표 버전을 고정하면 current가 바뀌어도 값·기준월은 같다 (SC-004). */
@DisplayName("거시지표 버전 고정 재조회")
class MacroReproducibilityTest extends RepositoryTestSupport {

    @Autowired private JdbcTemplate jdbc;

    @Test
    void pinnedPairRetainsObservationAndBaselineAfterPointerSwitch() {
        jdbc.update(
                """
                INSERT INTO macro_calculation_registry_version
                    (version_key, value_rule_fingerprint, change_reason, status, created_at)
                VALUES ('REPRO-CALC-1', repeat('a', 64), '검증', 'PUBLISHED', now())
                """);
        long registryId =
                jdbc.queryForObject(
                        "SELECT id FROM macro_calculation_registry_version"
                                + " WHERE version_key='REPRO-CALC-1'",
                        Long.class);
        jdbc.update(
                """
                INSERT INTO macro_display_version (version_key, change_reason, created_at)
                VALUES ('REPRO-DISPLAY-1', '검증', now())
                """);
        long displayId =
                jdbc.queryForObject(
                        "SELECT id FROM macro_display_version WHERE version_key='REPRO-DISPLAY-1'",
                        Long.class);
        long oldDataset = insertDataset("REPRO-DATA-1", registryId, "1.25", "2026-06-01");
        long newDataset = insertDataset("REPRO-DATA-2", registryId, "2.50", "2026-07-01");
        jdbc.update(
                """
                INSERT INTO macro_current_publication
                    (dataset_version_id, registry_version_id, display_version_id, switched_at)
                VALUES (?, ?, ?, now())
                """,
                oldDataset,
                registryId,
                displayId);

        List<Map<String, Object>> pinnedBefore = pinnedRows(oldDataset, registryId);
        jdbc.update(
                "UPDATE macro_current_publication SET dataset_version_id=?, switched_at=now()"
                        + " WHERE singleton_key='X'",
                newDataset);
        List<Map<String, Object>> pinnedAfter = pinnedRows(oldDataset, registryId);

        assertThat(pinnedAfter).isEqualTo(pinnedBefore);
        assertThat(pinnedAfter).hasSize(1);
        assertThat(pinnedAfter.getFirst().get("value")).isEqualTo("1.25");
        assertThat(pinnedAfter.getFirst().get("reference_month").toString())
                .isEqualTo("2026-06-01");
        assertThat(pinnedRows(newDataset, registryId).getFirst().get("value")).isEqualTo("2.50");
    }

    private long insertDataset(String key, long registryId, String value, String month) {
        jdbc.update(
                """
                INSERT INTO macro_dataset_version
                    (version_key, processing_registry_version_id, value_rule_fingerprint,
                     status, complete_indicator_count, published_at, created_at)
                VALUES (?, ?, repeat('a', 64), 'PUBLISHED', 17, now(), now())
                """,
                key,
                registryId);
        long datasetId =
                jdbc.queryForObject(
                        "SELECT id FROM macro_dataset_version WHERE version_key=?",
                        Long.class,
                        key);
        jdbc.update(
                """
                INSERT INTO macro_observation
                    (dataset_version_id, indicator_id, target_month,
                     transformed_value, received_at)
                VALUES (?, 'consumer_price_index', ?::date, to_jsonb(?::text), now())
                """,
                datasetId,
                month,
                value);
        jdbc.update(
                """
                INSERT INTO macro_baseline
                    (dataset_version_id, registry_version_id, scope_id,
                     reference_month, reference_month_held_back_by, computed_at)
                VALUES (?, ?, 'ALL', ?::date, '[]'::jsonb, now())
                """,
                datasetId,
                registryId,
                month);
        return datasetId;
    }

    private List<Map<String, Object>> pinnedRows(long datasetId, long registryId) {
        return jdbc.queryForList(
                """
                SELECT o.transformed_value #>> '{}' AS value,
                       b.reference_month AS reference_month
                FROM macro_observation o
                JOIN macro_baseline b ON b.dataset_version_id=o.dataset_version_id
                    AND b.registry_version_id=? AND b.scope_id='ALL'
                WHERE o.dataset_version_id=? AND o.indicator_id='consumer_price_index'
                """,
                registryId,
                datasetId);
    }
}
