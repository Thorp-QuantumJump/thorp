package com.ssafy.thorp.paramsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.ssafy.thorp.backtest.application.event.BacktestFinished;
import com.ssafy.thorp.backtest.application.port.BacktestJobPublisher;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.paramsearch.application.command.CreateParamSearchCommand;
import com.ssafy.thorp.paramsearch.application.repository.ParamSearchRepository;
import com.ssafy.thorp.paramsearch.application.repository.SearchCandidateRepository;
import com.ssafy.thorp.paramsearch.application.result.ParamSearchResult;
import com.ssafy.thorp.paramsearch.application.service.ParamSearchService;
import com.ssafy.thorp.paramsearch.domain.model.CandidateStatus;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.strategy.application.port.StrategyRegistration;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 접수가 <b>실제 발행기</b>로 004까지 닿는지(T043 보강).
 *
 * <p>{@link ParamSearchIntakeIT}·{@code ParamSearchApiIT}는 {@code CandidateJobLauncher}를 목으로 바꾼다.
 * 거기서 보는 것이 큐가 아니라 묶음 상태이기 때문인데, 그 결과 <b>실제 런처가 한 번도 실행되지 않아</b> 접수가 004에 닿는 경로 전체가 검증 밖에 있었다. 이
 * 클래스가 그 경로만 본다 — 여기서 잡히는 것은 묶음의 셈이 아니라 <b>실행 행이 실제로 생기는가</b>다.
 *
 * <p>목으로 가려져 있던 세 가지를 고정한다.
 *
 * <ol>
 *   <li>기준 전략의 규칙 버전이 스냅샷에 실린다. 기준은 사용자가 고른 전략이 아니라 등록표가 지정한 역할이라, 요청 전략만 담으면 {@code null}이 되어 004가
 *       422로 접수를 죽인다(FR-013d).
 *   <li>발행 결과로 받는 실행 행 id가 실재한다. 접수 결과의 식별자는 <b>요청</b>의 것이고 실행 행은 자기 {@code backtest_id}를 따로 가져, 실행
 *       저장소를 요청 식별자로 조회하면 언제나 빈 결과다.
 *   <li>요청자 없이는 접수하지 않는다. {@code backtest_request.requester_id}가 NOT NULL이라(spec 004 R-12) 요청자가 비면
 *       발행 직전에 처리되지 않은 예외로 죽는다.
 * </ol>
 *
 * <p>전략 ID를 적지 않는다(FR-025). 대상은 "등록표 활성 ∩ 004 지원 ∩ 축이 있는 전략 ∩ 기준이 아닌 전략"으로 <b>조회해서</b> 고른다. 축이 없는
 * 전략을 거르는 이유는 자동 탐색의 대상이 축을 가진 전략이기 때문이고, 기준을 빼는 이유는 <b>그것이 깨져 있던 조건</b>이기 때문이다 — 요청 전략에 기준이 우연히
 * 섞이면 결함이 가려진다.
 */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "thorp.backtest.republish.enabled=false",
            "thorp.param-search.enabled=true",
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
class ParamSearchLaunchIT extends PostgresTestSupport {

    private static final List<String> UNIVERSE = List.of("091160.XKRX", "305720.XKRX");

    @Autowired private ParamSearchService paramSearchService;
    @Autowired private ParamSearchRepository paramSearchRepository;
    @Autowired private SearchCandidateRepository candidateRepository;
    @Autowired private ParameterRegistryPort registry;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private TransactionTemplate transactionTemplate;

    /** 브로커는 이 테스트의 관심사가 아니다. 발행 자체는 004의 테스트가 본다. */
    @MockitoBean private BacktestJobPublisher publisher;

    private long requesterId;

    @BeforeEach
    void prepare() {
        jdbcTemplate.update("DELETE FROM search_candidate");
        jdbcTemplate.update("DELETE FROM param_search");
        requesterId = newUser();
    }

    @Test
    @DisplayName("요청 전략에 기준이 없어도 접수가 기준과 후보를 실제로 발행한다")
    void acceptsAndLaunchesWithRealLauncher() {
        List<String> targets = searchableStrategyIdsExcludingBaseline();
        assumeThat(targets).as("축이 있고 기준이 아닌 실행 가능 전략이 있어야 이 경로를 시험할 수 있다").isNotEmpty();

        ParamSearchResult accepted = paramSearchService.create(createCommand(targets));

        ParamSearch saved = paramSearchRepository.findBySearchId(accepted.searchId()).orElseThrow();
        assertThat(saved.getOwnerUserId()).isEqualTo(requesterId);

        // (1)(2) 기준이 실제로 발행됐고 그 id가 실행 행을 가리킨다.
        Long baselineJobId = saved.getBaselineJobId();
        assertThat(baselineJobId).as("등록표가 기준 전략을 지정했으므로 기준 1건이 함께 돈다(FR-013d)").isNotNull();
        assertThat(runExists(baselineJobId))
                .as("발행기가 돌려준 id가 backtest_run 에 없으면 요청 식별자로 실행을 찾은 것이다")
                .isTrue();

        // 발행된 후보도 같다 — 후보는 이 id로 자기 결과를 찾는다.
        List<SearchCandidate> dispatched =
                candidateRepository.findByParamSearchId(saved.getId()).stream()
                        .filter(candidate -> candidate.getBacktestJobId() != null)
                        .toList();
        assertThat(dispatched).as("발행 창만큼은 나가야 한다").isNotEmpty();
        assertThat(dispatched)
                .allSatisfy(
                        candidate -> assertThat(runExists(candidate.getBacktestJobId())).isTrue());
        assertThat(dispatched.size())
                .isLessThanOrEqualTo((int) saved.getLimits().get("dispatchWindow"));

        // 기준 실행과 후보 실행은 서로 다른 실행 행이다.
        assertThat(dispatched)
                .noneSatisfy(
                        candidate ->
                                assertThat(candidate.getBacktestJobId()).isEqualTo(baselineJobId));
    }

    @Test
    @DisplayName("같은 조건을 두 번 접수해도 둘 다 받고, 두 묶음의 후보가 같은 실행을 나눠 쓴다")
    void sameConditionTwiceSharesRuns() {
        List<String> targets = searchableStrategyIdsExcludingBaseline();
        assumeThat(targets).isNotEmpty();

        ParamSearchResult first = paramSearchService.create(createCommand(targets));
        // 두 번째 접수가 500 이던 자리다(S15P21E205-221). 004는 같은 조건의 요청을 살아 있는 실행에
        // 합류시키는데(spec 004 R-02), search_candidate.backtest_job_id 에 UNIQUE 가 걸려 있어
        // 두 번째 후보가 같은 실행 행을 받는 순간 제약에 걸렸고 묶음 행까지 통째로 롤백됐다.
        ParamSearchResult second = paramSearchService.create(createCommand(targets));

        ParamSearch before = paramSearchRepository.findBySearchId(first.searchId()).orElseThrow();
        ParamSearch after = paramSearchRepository.findBySearchId(second.searchId()).orElseThrow();
        // 같은 조건이어도 묶음은 항상 새로 생긴다(FR-013c, 계약 §7).
        assertThat(after.getSearchId()).isNotEqualTo(before.getSearchId());

        List<Long> beforeJobs = dispatchedJobIds(before);
        List<Long> afterJobs = dispatchedJobIds(after);
        assertThat(afterJobs).as("발행 창만큼은 나가야 한다").isNotEmpty();
        // 조합이 같으니 004가 돌려주는 실행도 같다 — 아낀 계산이 곧 재사용의 값이다.
        assertThat(afterJobs).isEqualTo(beforeJobs);
        assertThat(after.getBaselineJobId()).isEqualTo(before.getBaselineJobId());

        // 실행은 공유해도 후보 행은 묶음마다 따로 남는다.
        assertThat(candidateRepository.findAllByBacktestJobId(afterJobs.get(0)))
                .hasSize(2)
                .extracting(SearchCandidate::getParamSearchId)
                .containsExactlyInAnyOrder(before.getId(), after.getId());
    }

    @Test
    @DisplayName("공유한 실행이 끝나면 걸려 있던 두 묶음의 후보가 함께 끝난다")
    void sharedRunFinishesEveryCandidateOnIt() {
        List<String> targets = searchableStrategyIdsExcludingBaseline();
        assumeThat(targets).isNotEmpty();

        ParamSearchResult first = paramSearchService.create(createCommand(targets));
        ParamSearchResult second = paramSearchService.create(createCommand(targets));
        ParamSearch before = paramSearchRepository.findBySearchId(first.searchId()).orElseThrow();
        long sharedJobId = dispatchedJobIds(before).get(0);

        // 004가 발행하는 것과 같은 이벤트를 같은 경로(AFTER_COMMIT 리스너)로 흘린다.
        transactionTemplate.executeWithoutResult(
                status ->
                        events.publishEvent(
                                new BacktestFinished(
                                        sharedJobId,
                                        "BT-STUB-" + sharedJobId,
                                        RunStatus.COMPLETED,
                                        null)));

        // 하나만 끝내면 나머지 묶음이 영영 RUNNING 으로 남는다 — 실행은 다시 오지 않는다.
        assertThat(candidateRepository.findAllByBacktestJobId(sharedJobId))
                .hasSize(2)
                .allSatisfy(
                        candidate ->
                                assertThat(candidate.getStatus())
                                        .isEqualTo(CandidateStatus.COMPLETED));
        assertThat(paramSearchRepository.findBySearchId(second.searchId()).orElseThrow())
                .satisfies(reloaded -> assertThat(reloaded.getCounts().completed()).isEqualTo(1));
    }

    @Test
    @DisplayName("기준 전략의 규칙 버전이 실행 조건 스냅샷에 들어 있다")
    void executionPlanCarriesBaselineRulesVersion() {
        List<String> targets = searchableStrategyIdsExcludingBaseline();
        assumeThat(targets).isNotEmpty();
        String baselineStrategyId = registry.baselineStrategyId().orElse(null);
        assumeThat(baselineStrategyId).as("등록표가 기준 전략을 지정했을 때만 성립한다").isNotNull();

        ParamSearchResult accepted = paramSearchService.create(createCommand(targets));
        ParamSearch saved = paramSearchRepository.findBySearchId(accepted.searchId()).orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> rulesVersions =
                (Map<String, Object>) saved.getExecutionPlan().get("rulesVersionByStrategy");
        assertThat(rulesVersions)
                .as("기준 전략은 요청 전략이 아니므로 빠뜨리기 쉽다 — 빠지면 004가 422로 거부한다")
                .containsKey(baselineStrategyId);
        assertThat(rulesVersions.get(baselineStrategyId)).isNotNull();
    }

    /** 등록표 활성 ∩ 004 지원 ∩ 축 보유 ∩ 기준 아님. 전략 ID를 테스트에 적지 않는다(FR-025). */
    private List<String> searchableStrategyIdsExcludingBaseline() {
        List<String> registered =
                jdbcTemplate.queryForList(
                        "SELECT DISTINCT strategy_id FROM strategy_rules WHERE status ="
                                + " 'REGISTERED'",
                        String.class);
        String baselineStrategyId = registry.baselineStrategyId().orElse(null);
        return registry.activeStrategies().stream()
                .filter(registration -> !registration.axes().isEmpty())
                .map(StrategyRegistration::strategyId)
                .filter(registered::contains)
                .filter(strategyId -> !strategyId.equals(baselineStrategyId))
                .toList();
    }

    private CreateParamSearchCommand createCommand(List<String> strategyIds) {
        return new CreateParamSearchCommand(
                requesterId, UNIVERSE, strategyIds, null, null, null, Map.of(), registry.version());
    }

    /** 이 묶음이 실제로 발행한 실행 행 id를 순번대로. 후보가 자기 결과를 찾는 그 값이다. */
    private List<Long> dispatchedJobIds(ParamSearch search) {
        return candidateRepository.findByParamSearchId(search.getId()).stream()
                .filter(candidate -> candidate.getBacktestJobId() != null)
                .map(SearchCandidate::getBacktestJobId)
                .toList();
    }

    private boolean runExists(Long runId) {
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM backtest_run WHERE id = ?", Integer.class, runId);
        return count != null && count == 1;
    }

    private long newUser() {
        String suffix = String.valueOf(System.nanoTime());
        String email = "launch-" + suffix + "@example.com";
        jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, nickname, phone_number, status,"
                        + " paper_ranking_enabled, live_ranking_enabled, ranking_display_nickname,"
                        + " created_at, updated_at)"
                        + " VALUES (?, 'x', ?, ?, 'ACTIVE', false, false, false, NOW(), NOW())",
                email,
                "u" + suffix,
                suffix);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);
    }
}
