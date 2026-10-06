package com.ssafy.thorp.macro;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.macro.application.repository.MacroSnapshotRepository;
import com.ssafy.thorp.macro.application.repository.MacroSnapshotRepository.BaselineRow;
import com.ssafy.thorp.macro.application.repository.MacroSnapshotRepository.CurrentPointer;
import com.ssafy.thorp.macro.application.repository.MacroSnapshotRepository.MonthKey;
import com.ssafy.thorp.macro.application.repository.MacroSnapshotRepository.ObservationRow;
import com.ssafy.thorp.macro.infrastructure.persistence.MacroSnapshotRepositoryImpl;
import com.ssafy.thorp.support.RepositoryTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 같은 자료·계산 등록표 버전을 고정하면 current가 바뀌어도 값·기준월은 같다 (SC-004).
 *
 * <p>다시 읽는 것은 운영 조회 통로인 {@link MacroSnapshotRepository}다. 시험이 자기 SELECT로 읽으면 표의 행이 그대로라는 것만 보게 되고,
 * 조회가 고정한 버전 대신 current를 따라가도 통과한다.
 */
@DisplayName("거시지표 버전 고정 재조회")
class MacroReproducibilityTest extends RepositoryTestSupport {

    private static final String INDICATOR = "consumer_price_index";

    @Autowired private JdbcTemplate jdbc;

    private MacroSnapshotRepository snapshots;

    @BeforeEach
    void setUp() {
        snapshots = new MacroSnapshotRepositoryImpl(jdbc, new ObjectMapper());
    }

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

        Pinned before = pinned(oldDataset, registryId);
        jdbc.update(
                "UPDATE macro_current_publication SET dataset_version_id=?, switched_at=now()"
                        + " WHERE singleton_key='X'",
                newDataset);
        Pinned after = pinned(oldDataset, registryId);

        assertThat(snapshots.findCurrentPointer())
                .get()
                .extracting(CurrentPointer::datasetVersionId)
                .isEqualTo(newDataset);
        assertThat(after).isEqualTo(before);
        assertThat(after.value()).isEqualByComparingTo("1.25");
        assertThat(after.referenceMonth()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(pinned(newDataset, registryId).value()).isEqualByComparingTo("2.50");
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
                VALUES (?, ?, ?::date, to_jsonb(?::text), now())
                """,
                datasetId,
                INDICATOR,
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

    /** 고정한 버전 쌍의 전체 기준월과 그 달의 변환값을 운영 조회 통로로 다시 읽는다. */
    private Pinned pinned(long datasetId, long registryId) {
        BaselineRow all =
                snapshots.findBaselines(datasetId, registryId).stream()
                        .filter(baseline -> baseline.scopeId().equals("ALL"))
                        .findFirst()
                        .orElseThrow();
        List<ObservationRow> observations =
                snapshots.findObservations(
                        datasetId, List.of(new MonthKey(INDICATOR, all.referenceMonth())));
        assertThat(observations).hasSize(1);
        return new Pinned(observations.getFirst().transformedValue(), all.referenceMonth());
    }

    private record Pinned(BigDecimal value, LocalDate referenceMonth) {}
}
