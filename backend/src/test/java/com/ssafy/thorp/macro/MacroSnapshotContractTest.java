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
import com.ssafy.thorp.macro.support.MacroRegistrySeed;
import com.ssafy.thorp.support.PostgresTestSupport;
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

        assertThat(byId.get("dubai_crude").agency()).isEqualTo("국제통화기금(IMF)");
        assertThat(byId.get("dubai_crude").source()).contains("한국은행");
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
                WHERE indicator_id = 'all_industry_production' AND target_month = '2026-06-01'::date
                """);

        MacroSnapshotResult.IndicatorSummary indicator =
                service.getSnapshot().indicators().stream()
                        .filter(i -> i.indicatorId().equals("all_industry_production"))
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
        seedFailedCollection("mining_manufacturing_production");

        MacroSnapshotResult snapshot = service.getSnapshot();

        assertThat(snapshot.collection().failedIndicatorIds())
                .containsExactly("mining_manufacturing_production");
        assertThat(statusOf(snapshot, "mining_manufacturing_production"))
                .isEqualTo("COLLECTION_FAILED");
        // 어제 발행한 자료는 그대로 있다. 실패는 자료를 지우지 않는다(FR-011).
        assertThat(valueOf(snapshot, "mining_manufacturing_production")).isNotNull();
        assertThat(statusOf(snapshot, "all_industry_production")).isEqualTo("READY");
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
        seedFailedCollection("mining_manufacturing_production");
        jdbc.update("UPDATE macro_dataset_version SET is_sample_data = TRUE");

        assertThat(statusOf(service.getSnapshot(), "mining_manufacturing_production"))
                .isEqualTo("EXAMPLE");
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
        MacroRegistrySeed.seedRegistry(jdbc);
    }

    private void seedPublishedDataset() {
        MacroRegistrySeed.seedPublishedDataset(jdbc);
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
                VALUES (?, 'all_industry_production', 'kosis', 'SUCCEEDED', now())
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
