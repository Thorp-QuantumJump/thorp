package com.ssafy.thorp.paramsearch.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.backtest.infrastructure.messaging.MessageJsonPersistenceConfig;
import com.ssafy.thorp.common.config.JpaAuditingConfig;
import com.ssafy.thorp.paramsearch.domain.model.CandidateStatus;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearchStatus;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import com.ssafy.thorp.paramsearch.domain.model.SearchCounts;
import com.ssafy.thorp.paramsearch.domain.model.SearchMethod;
import com.ssafy.thorp.paramsearch.domain.model.SelectionRule;
import com.ssafy.thorp.paramsearch.domain.model.StopReason;
import com.ssafy.thorp.paramsearch.domain.model.ValidationLabel;
import com.ssafy.thorp.paramsearch.domain.vo.GridCoordinates;
import com.ssafy.thorp.paramsearch.domain.vo.ParameterCombination;
import com.ssafy.thorp.paramsearch.domain.vo.SearchSpace;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V3 마이그레이션과 Entity 매핑이 어긋나지 않는지 운영(prod)과 같은 조건으로 검증한다.
 *
 * <p>Flyway·{@code ddl-auto=validate} 설정과 PostgreSQL 컨테이너는 {@link PostgresTestSupport}가 제공하므로, 스키마와
 * 매핑이 어긋나면 <b>컨텍스트 로딩 자체가 실패</b>한다. 로컬 프로필은 {@code ddl-auto=update} + Flyway 비활성이라 이 불일치를 잡지 못한다 —
 * 그래서 이 테스트가 필요하다.
 *
 * <p>컬럼 목록을 눈으로 맞춰 두는 단언이 함께 있는 이유는 {@code FlywayMigrationTest}와 같다. Hibernate는 타입 불일치를 경고로만 남기고
 * 통과시킨다.
 */
@DataJpaTest
@Import({
    JpaAuditingConfig.class,
    // JSON 컬럼 직렬화를 운영과 같은 ObjectMapper에 맡긴다. @DataJpaTest는 @Configuration을 스캔하지
    // 않아 이것이 없으면 Hibernate 기본 매퍼(findAndRegisterModules)가 쓰이는데, 그러면 테스트
    // 클래스패스에만 있는 jackson-module-scala가 untyped JSON 배열을 Scala List로 만들고 decimal도
    // 문자열이 아니라 숫자로 저장된다 — 운영과 다른 조건에서 통과하는 테스트가 된다.
    MessageJsonPersistenceConfig.class,
    ParamSearchRepositoryImpl.class,
    SearchCandidateRepositoryImpl.class
})
class ParamSearchMigrationTest extends PostgresTestSupport {

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-10T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ParamSearchRepositoryImpl paramSearchRepository;
    @Autowired private SearchCandidateRepositoryImpl candidateRepository;
    @Autowired private TestEntityManager entityManager;

    @Test
    @DisplayName("V3가 적용되어 flyway_schema_history에 성공으로 기록된다")
    void v3IsApplied() {
        Integer applied =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM flyway_schema_history"
                                + " WHERE version = '3' AND success = TRUE",
                        Integer.class);

        assertThat(applied).isEqualTo(1);
    }

    @Test
    @DisplayName("V3가 param_search를 Entity 매핑과 같은 컬럼으로 만든다")
    void v3CreatesParamSearchColumns() {
        assertThat(columnsOf("param_search"))
                .containsExactlyInAnyOrder(
                        "id",
                        "search_id",
                        "owner_user_id",
                        "strategy_ids",
                        "instrument_ids",
                        "registry_version",
                        "search_space",
                        "evaluation_split",
                        "search_method",
                        "random_seed",
                        "selection_rule",
                        "limits",
                        "reference_versions",
                        "baseline_job_id",
                        "status",
                        "stop_reason",
                        "counts",
                        "selection",
                        "data_version_changed",
                        "started_at",
                        "finished_at",
                        "created_at",
                        "updated_at");
    }

    @Test
    @DisplayName("V3가 search_candidate를 Entity 매핑과 같은 컬럼으로 만든다")
    void v3CreatesSearchCandidateColumns() {
        assertThat(columnsOf("search_candidate"))
                .containsExactlyInAnyOrder(
                        "id",
                        "param_search_id",
                        "candidate_no",
                        "strategy_id",
                        "parameters",
                        "grid_coordinates",
                        "backtest_job_id",
                        "status",
                        "reason_code",
                        "reason_detail",
                        "stability",
                        "retry_count",
                        "period_performance",
                        "validation_label",
                        "selected",
                        "selection_reason",
                        "created_at",
                        "updated_at");
    }

    @Test
    @DisplayName("V3가 PK용 시퀀스 둘을 만들고 후보 시퀀스 증분이 allocationSize(50)와 같다")
    void v3CreatesSequencesWithMatchingIncrement() {
        assertThat(sequenceExists("param_search_seq")).isEqualTo(1);
        assertThat(sequenceExists("search_candidate_seq")).isEqualTo(1);

        // 증분이 allocationSize와 다르면 Hibernate가 이미 쓴 id를 다시 발급해 PK 충돌이 난다.
        // increment_by는 pg_sequences의 컬럼이다 — information_schema.sequences는 같은 값을
        // 'increment'라는 이름의 문자열로 준다.
        Long increment =
                jdbcTemplate.queryForObject(
                        "SELECT increment_by FROM pg_sequences"
                                + " WHERE schemaname = 'public'"
                                + " AND sequencename = 'search_candidate_seq'",
                        Long.class);
        assertThat(increment).isEqualTo(50L);
    }

    @Test
    @DisplayName("V3가 묶음 UNIQUE·후보 순번 UNIQUE·Job UNIQUE와 FK를 만든다")
    void v3CreatesConstraints() {
        List<String> foreignKeyTables =
                jdbcTemplate.queryForList(
                        "SELECT LOWER(table_name) FROM information_schema.table_constraints"
                                + " WHERE constraint_type = 'FOREIGN KEY'"
                                + " AND LOWER(table_name) IN ('param_search', 'search_candidate')",
                        String.class);
        assertThat(foreignKeyTables).contains("param_search", "search_candidate");

        Integer candidateNoUnique =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.table_constraints"
                                + " WHERE LOWER(constraint_name) = 'uk_search_candidate_search_no'",
                        Integer.class);
        assertThat(candidateNoUnique).isEqualTo(1);

        // backtest_job_id는 부분 UNIQUE 인덱스라 table_constraints가 아니라 pg_indexes에 있다.
        Integer jobUnique =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM pg_indexes"
                                + " WHERE schemaname = 'public'"
                                + " AND indexname = 'uk_search_candidate_job'",
                        Integer.class);
        assertThat(jobUnique).isEqualTo(1);
    }

    @Test
    @DisplayName("V3 스키마에 묶음과 후보를 저장하고 다시 읽을 수 있다")
    void entitiesRoundTripOnMigratedSchema() {
        ParamSearch saved = paramSearchRepository.save(newSearch());
        entityManager.flush();

        assertThat(saved.getId()).isNotNull();
        // searchId는 저장 시점에 PK와 고정 Clock 날짜로 조립된다.
        assertThat(saved.getSearchId().value()).isEqualTo("PS-20260910-" + pad(saved.getId()));

        List<SearchCandidate> savedCandidates =
                candidateRepository.saveAll(
                        saved.getId(),
                        List.of(
                                SearchCandidate.pending(
                                        1,
                                        "SECTOR_MOMENTUM",
                                        ParameterCombination.of(
                                                Map.of("lookbackDays", 60, "topN", 3)),
                                        GridCoordinates.of(Map.of("lookbackDays", 1, "topN", 1))),
                                SearchCandidate.excluded(
                                        2,
                                        "SECTOR_MOMENTUM",
                                        ParameterCombination.of(
                                                Map.of("lookbackDays", 60, "topN", 12)),
                                        GridCoordinates.of(Map.of("lookbackDays", 1, "topN", 2)),
                                        "TOP_N_EXCEEDS_UNIVERSE",
                                        "상위 12개를 고르도록 설정했지만 후보 섹터가 2개입니다")));
        entityManager.flush();
        entityManager.clear();

        assertThat(savedCandidates).hasSize(2);
        assertThat(paramSearchRepository.findBySearchId(saved.getSearchId().value()))
                .get()
                .satisfies(
                        search -> {
                            assertThat(search.getStatus()).isEqualTo(ParamSearchStatus.QUEUED);
                            assertThat(search.getSearchMethod()).isEqualTo(SearchMethod.FULL_GRID);
                            assertThat(search.getOwnerUserId()).isNull();
                            assertThat(search.getRandomSeed()).isNull();
                            assertThat(search.getEvaluationSplit()).isNull();
                            assertThat(search.getRegistryVersion())
                                    .isEqualTo("PROVISIONAL-20260910");
                            // JSONB 왕복에서 탐색 공간의 축·값 순서가 보존되어야 저장된 그리드 좌표가 뜻을 유지한다.
                            assertThat(search.getSearchSpace().valuesOf("lookbackDays"))
                                    .containsExactly(20, 60, 120);
                            assertThat(search.getSelectionRule().displayCount()).isEqualTo(5);
                            assertThat(search.getSelectionRule().isProvisional("displayCount"))
                                    .isTrue();
                            assertThat(search.getCounts()).isEqualTo(SearchCounts.initial(6, 5, 1));
                        });

        List<SearchCandidate> reloaded = candidateRepository.findByParamSearchId(saved.getId());
        assertThat(reloaded).hasSize(2);
        assertThat(reloaded.get(0))
                .satisfies(
                        candidate -> {
                            assertThat(candidate.getCandidateNo()).isEqualTo(1);
                            assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.PENDING);
                            assertThat(candidate.getValidationLabel())
                                    .isEqualTo(ValidationLabel.UNVALIDATED);
                            assertThat(candidate.getBacktestJobId()).isNull();
                            assertThat(candidate.getRetryCount()).isZero();
                            assertThat(candidate.getGridCoordinates().indices())
                                    .containsEntry("lookbackDays", 1)
                                    .containsEntry("topN", 1);
                        });
        assertThat(reloaded.get(1))
                .satisfies(
                        candidate -> {
                            assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.EXCLUDED);
                            assertThat(candidate.getReasonCode())
                                    .isEqualTo("TOP_N_EXCEEDS_UNIVERSE");
                            // 계산하지 않은 후보의 성과 자리는 비어 있어야 한다 — 0으로 채우지 않는다(원칙 VI).
                            assertThat(candidate.getPeriodPerformance()).isNull();
                            assertThat(candidate.getStability()).isNull();
                        });
    }

    @Test
    @DisplayName("진행 상태 갱신이 접수 스냅샷을 건드리지 않는다")
    void progressUpdateKeepsIntakeSnapshot() {
        ParamSearch saved = paramSearchRepository.save(newSearch());
        entityManager.flush();

        ParamSearch running = paramSearchRepository.update(saved.markRunning(Instant.now()));
        ParamSearch partial =
                paramSearchRepository.update(
                        running.withCounts(new SearchCounts(6, 5, 3, 1, 1, 1))
                                .markPartial(StopReason.CANCELLED, Instant.now()));
        entityManager.flush();
        entityManager.clear();

        assertThat(paramSearchRepository.findById(partial.getId()))
                .get()
                .satisfies(
                        search -> {
                            assertThat(search.getStatus()).isEqualTo(ParamSearchStatus.PARTIAL);
                            assertThat(search.getStopReason()).isEqualTo(StopReason.CANCELLED);
                            assertThat(search.getCounts().skipped()).isEqualTo(1);
                            assertThat(search.getStartedAt()).isNotNull();
                            assertThat(search.getFinishedAt()).isNotNull();
                            // 접수 시점 스냅샷은 그대로다(FR-013c).
                            assertThat(search.getSelectionRule()).isEqualTo(newSelectionRule());
                            assertThat(search.getSearchSpace().valuesOf("topN"))
                                    .containsExactly(2, 3);
                            assertThat(search.getRegistryVersion())
                                    .isEqualTo("PROVISIONAL-20260910");
                        });
    }

    @Test
    @DisplayName("후보 갱신이 조합과 순번을 건드리지 않고 재시도 횟수만 늘린다")
    void candidateUpdateKeepsCombination() {
        insertBacktestJob(9001L);
        insertBacktestJob(9002L);
        ParamSearch saved = paramSearchRepository.save(newSearch());
        SearchCandidate candidate =
                candidateRepository
                        .saveAll(
                                saved.getId(),
                                List.of(
                                        SearchCandidate.pending(
                                                1,
                                                "SECTOR_MOMENTUM",
                                                ParameterCombination.of(Map.of("topN", 3)),
                                                GridCoordinates.of(Map.of("topN", 1)))))
                        .get(0);
        entityManager.flush();

        SearchCandidate failed =
                candidateRepository.update(
                        candidateRepository
                                .update(candidate.markDispatched(9001L))
                                .markFailed("DATA_GAP", "자료가 부족합니다"));
        entityManager.flush();
        entityManager.clear();

        // 재시도는 새 Job으로 다시 발행되고 이전 실패 사유를 지운다(FR-024).
        SearchCandidate retried = candidateRepository.update(failed.markDispatched(9002L));
        entityManager.flush();
        entityManager.clear();

        assertThat(candidateRepository.findByBacktestJobId(9002L))
                .get()
                .satisfies(
                        reloaded -> {
                            assertThat(reloaded.getRetryCount()).isEqualTo(1);
                            assertThat(reloaded.getStatus()).isEqualTo(CandidateStatus.DISPATCHED);
                            assertThat(reloaded.getReasonCode()).isNull();
                            assertThat(reloaded.getCandidateNo()).isEqualTo(1);
                            assertThat(reloaded.getParameters().valueOf("topN")).isEqualTo(3);
                        });
        assertThat(retried.getId()).isEqualTo(candidate.getId());
    }

    @Test
    @DisplayName("발행 창은 PENDING 후보를 순번대로 창 크기만큼만 가져온다")
    void dispatchWindowReadsPendingInOrder() {
        ParamSearch saved = paramSearchRepository.save(newSearch());
        candidateRepository.saveAll(
                saved.getId(), List.of(pendingNo(1), pendingNo(2), pendingNo(3), pendingNo(4)));
        entityManager.flush();
        entityManager.clear();

        List<SearchCandidate> window =
                candidateRepository.findNextByStatus(saved.getId(), CandidateStatus.PENDING, 2);

        assertThat(window).extracting(SearchCandidate::getCandidateNo).containsExactly(1, 2);
        assertThat(candidateRepository.countByStatus(saved.getId(), CandidateStatus.PENDING))
                .isEqualTo(4);
    }

    /**
     * 후보에 붙일 실제 {@code backtest_run} 행을 만든다(004 T003 에서 {@code backtest_job} 이 개명됐다).
     *
     * <p>{@code search_candidate.backtest_job_id}가 {@code backtest_run(id)}를 참조하므로 없는 Job id를 쓰면 FK
     * 위반이다. 004의 접수 경로를 부르지 않고 행만 넣는 이유: 이 테스트가 검증하는 것은 007 스키마이고, Job 생성 규약은 {@code
     * FlywayMigrationTest}가 이미 본다.
     */
    private void insertBacktestJob(long id) {
        jdbcTemplate.update(
                "INSERT INTO backtest_run (id, backtest_id, strategy_id, strategy_version,"
                        + " parameters, instrument_ids, start_date, end_date, initial_cash,"
                        + " position_sizing_type, random_seed, status, created_at, updated_at)"
                        + " VALUES (?, ?, 'SECTOR_MOMENTUM', '1.0.0', '{}'::jsonb,"
                        + " '[\"091160.XKRX\"]'::jsonb, DATE '2019-01-01', DATE '2023-12-31',"
                        + " 10000000.00, 'STRATEGY_DEFINED', 42, 'QUEUED', NOW(), NOW())",
                id,
                "BT-20260910-" + pad(id));
    }

    private static SearchCandidate pendingNo(int candidateNo) {
        return SearchCandidate.pending(
                candidateNo,
                "SECTOR_MOMENTUM",
                ParameterCombination.of(Map.of("topN", candidateNo)),
                GridCoordinates.of(Map.of("topN", candidateNo - 1)));
    }

    private static ParamSearch newSearch() {
        return ParamSearch.create(
                null,
                List.of("SECTOR_MOMENTUM"),
                List.of("091160.XKRX", "091170.XKRX"),
                "PROVISIONAL-20260910",
                SearchSpace.of(
                        Map.of(
                                "lookbackDays", List.of(20, 60, 120),
                                "topN", List.of(2, 3))),
                newSelectionRule(),
                null,
                Map.of("maxCandidates", 1000, "provisional", List.of("maxCandidates")),
                Map.of("engineVersion", "0.1.0"),
                SearchCounts.initial(6, 5, 1));
    }

    private static SelectionRule newSelectionRule() {
        return new SelectionRule(
                new SelectionRule.DrawdownFilter("NOT_WORSE_THAN_BASELINE", "EQUAL_WEIGHT"),
                new SelectionRule.StabilityFilter("BATCH_PERCENTILE", 50),
                "NET_RETURN",
                5,
                "SHOW_ALL_TIED",
                new SelectionRule.StabilityFormula(
                        "SINGLE_AXIS_ONE_STEP", "MEAN_ABS_NET_RETURN_DIFF"),
                List.of("displayCount"));
    }

    private static String pad(long sequence) {
        return String.format("%06d", sequence);
    }

    private Integer sequenceExists(String name) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.sequences"
                        + " WHERE LOWER(sequence_name) = ?",
                Integer.class,
                name);
    }

    /** 애플리케이션 스키마(public)의 컬럼만 센다. */
    private List<String> columnsOf(String table) {
        return jdbcTemplate.queryForList(
                "SELECT LOWER(column_name) FROM information_schema.columns"
                        + " WHERE LOWER(table_schema) = 'public' AND LOWER(table_name) = ?",
                String.class,
                table);
    }
}
