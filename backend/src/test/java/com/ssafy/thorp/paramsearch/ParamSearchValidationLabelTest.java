package com.ssafy.thorp.paramsearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.paramsearch.application.command.CreateParamSearchCommand;
import com.ssafy.thorp.paramsearch.application.command.FindCandidatesQuery;
import com.ssafy.thorp.paramsearch.application.command.PreviewParamSearchCommand;
import com.ssafy.thorp.paramsearch.application.port.CandidateJobLauncher;
import com.ssafy.thorp.paramsearch.application.repository.ParamSearchRepository;
import com.ssafy.thorp.paramsearch.application.repository.SearchCandidateRepository;
import com.ssafy.thorp.paramsearch.application.result.CandidatePageResult;
import com.ssafy.thorp.paramsearch.application.result.ParamSearchPreviewResult;
import com.ssafy.thorp.paramsearch.application.result.ParamSearchResult;
import com.ssafy.thorp.paramsearch.application.service.CandidateQueryService;
import com.ssafy.thorp.paramsearch.application.service.ParamSearchService;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import com.ssafy.thorp.paramsearch.domain.model.ValidationLabel;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.support.LauncherMockedIntegrationTestSupport;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 검증 상태 라벨(T108·T111·T112, FR-014·FR-015, research R5).
 *
 * <p><b>이 테스트가 통과한다고 해서 후보들이 검증을 통과한 것이 아니다.</b> 정반대다 — 평가 분할(PD-12)이 등록되지 않아 어느 구간이 탐색용이고 어느 구간이
 * 잠금인지 말할 수 없고, 그래서 전 후보가 {@code UNVALIDATED}다. 여기서 확인하는 것은 <b>그 사실이 빠짐없이 전달되는가</b>이지 성과의 품질이 아니다.
 *
 * <p>"아직 나누지 않았다"를 "나눠서 통과했다"로 읽히게 두면 사용자는 탐색 성과를 검증된 성과로 믿는다(원칙 VII). 그래서 실행 전·실행 중·결과 세 자리 모두에서
 * 같은 라벨과 같은 문장이 나오는지 본다 — 한 자리만 빠져도 사용자는 그 화면에서 미검증임을 모른다.
 */
class ParamSearchValidationLabelTest extends LauncherMockedIntegrationTestSupport {

    private static final List<String> UNIVERSE = List.of("091160.XKRX", "305720.XKRX");

    @Autowired private ParamSearchService paramSearchService;
    @Autowired private CandidateQueryService candidateQueryService;
    @Autowired private ParamSearchRepository paramSearchRepository;
    @Autowired private SearchCandidateRepository candidateRepository;
    @Autowired private ParameterRegistryPort registry;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private CandidateJobLauncher launcher;

    private final AtomicLong nextJobId = new AtomicLong(970_000L);

    @BeforeEach
    void prepare() {
        jdbcTemplate.update("DELETE FROM search_candidate");
        // 내 기록이 이 행들을 가리키므로 먼저 지운다(spec 006 my_record FK).
        jdbcTemplate.update("DELETE FROM my_record");
        jdbcTemplate.update("DELETE FROM param_search");
        jdbcTemplate.update("DELETE FROM backtest_result WHERE run_id >= 900000");
        jdbcTemplate.update("DELETE FROM backtest_run WHERE id >= 900000");
        nextJobId.set(970_000L);
        Mockito.when(launcher.launchCandidate(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenAnswer(invocation -> insertRun());
        Mockito.when(launcher.launchBaseline(ArgumentMatchers.any()))
                .thenAnswer(invocation -> insertRun());
    }

    @Test
    @DisplayName("평가 분할이 미등록이면 접수된 후보가 전부 UNVALIDATED다")
    void everyCandidateIsUnvalidatedWithoutASplit() {
        ParamSearch search = accept();

        assertThat(search.getEvaluationSplit())
                .as("분할이 등록되지 않았다는 것이 이 라벨의 이유다(research R5)")
                .isNull();
        List<SearchCandidate> candidates = candidateRepository.findByParamSearchId(search.getId());
        assertThat(candidates).isNotEmpty();
        assertThat(candidates)
                .allSatisfy(
                        candidate ->
                                assertThat(candidate.getValidationLabel())
                                        .isEqualTo(ValidationLabel.UNVALIDATED));
        assertThat(ValidationLabel.UNVALIDATED.isValidated())
                .as("미검증은 검증 통과가 아니다 — 이 테스트의 통과를 그렇게 읽으면 안 된다")
                .isFalse();
    }

    @Test
    @DisplayName("구간별 성과 자리는 비워 둔다 — 탐색 성과와 섞지 않는다")
    void periodPerformanceStaysEmpty() {
        ParamSearch search = accept();

        assertThat(candidateRepository.findByParamSearchId(search.getId()))
                .allSatisfy(
                        candidate ->
                                assertThat(candidate.getPeriodPerformance())
                                        .as("분할이 없는데 구간별 성과를 채우면 없는 구분을 만들어 낸다")
                                        .isNull());
    }

    @Test
    @DisplayName("실행 전·실행 중·결과 세 자리가 같은 라벨과 같은 문장을 말한다")
    void allThreeSurfacesSayTheSameThing() {
        ParamSearchPreviewResult preview = paramSearchService.preview(previewCommand());
        ParamSearch search = accept();
        ParamSearchResult status = paramSearchService.get(search.getSearchId().value());
        CandidatePageResult candidates =
                candidateQueryService.find(
                        search.getSearchId().value(),
                        FindCandidatesQuery.of(null, null, null, null, null));

        assertThat(preview.validationLabel()).isEqualTo(ValidationLabel.UNVALIDATED.name());
        assertThat(status.validationLabel())
                .as("폴링 중인 화면도 미검증임을 알아야 한다 — 결과를 본 뒤에 알면 늦다")
                .isEqualTo(preview.validationLabel());
        assertThat(status.validationNote())
                .as("문구가 흩어져 있으면 한 곳만 고쳐진다")
                .isEqualTo(preview.validationNote());
        assertThat(candidates.content())
                .allSatisfy(
                        item ->
                                assertThat(item.validationLabel())
                                        .isEqualTo(preview.validationLabel()));
    }

    @Test
    @DisplayName("검증 문구는 왜 미검증인지를 말한다")
    void theNoteExplainsWhy() {
        ParamSearchPreviewResult preview = paramSearchService.preview(previewCommand());

        assertThat(preview.validationNote())
                .as("'미검증'이라고만 하면 사용자는 무엇이 빠졌는지 모른다")
                .contains("평가 분할")
                .contains("미검증");
    }

    private ParamSearch accept() {
        ParamSearchResult accepted =
                paramSearchService.create(
                        new CreateParamSearchCommand(
                                null,
                                UNIVERSE,
                                SearchableStrategies.of(registry, jdbcTemplate),
                                null,
                                null,
                                null,
                                Map.of(),
                                registry.version()));
        return paramSearchRepository.findBySearchId(accepted.searchId()).orElseThrow();
    }

    private PreviewParamSearchCommand previewCommand() {
        return new PreviewParamSearchCommand(
                null,
                UNIVERSE,
                SearchableStrategies.of(registry, jdbcTemplate),
                null,
                null,
                null,
                Map.of());
    }

    private long insertRun() {
        long id = nextJobId.getAndIncrement();
        jdbcTemplate.update(
                "INSERT INTO backtest_run (id, backtest_id, strategy_id, strategy_version,"
                        + " parameters, instrument_ids, start_date, end_date, initial_cash,"
                        + " position_sizing_type, random_seed, status, created_at, updated_at)"
                        + " VALUES (?, ?, 'STUB', '1', '{}'::jsonb, '[\"091160.XKRX\"]'::jsonb,"
                        + " DATE '2020-01-02', DATE '2026-08-31', 10000000.00,"
                        + " 'STRATEGY_DEFINED', 42, 'QUEUED', NOW(), NOW())",
                id,
                "BT-STUB-" + id);
        return id;
    }
}
