package com.ssafy.thorp.paramsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.ssafy.thorp.backtest.application.event.BacktestFinished;
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
import com.ssafy.thorp.support.PortMockedIntegrationTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 같은 설정 조합을 두 번째로 실행하는 경로를 본다 — 후보 전부가 <b>이미 끝난 실행을 재사용</b>한다(spec 004 R-02).
 *
 * <p>여기서 잡는 것은 셈이 아니라 <b>묶음이 끝까지 가는가</b>다. 재사용은 후보를 발행하는 그 자리에서 완료를 다시 알리므로, 후보를 하나 전진시키면 곧바로 다음
 * 완료가 도착한다. 그 연쇄를 겹쳐 처리하면 후보마다 트랜잭션이 하나씩 쌓여 커넥션 풀이 마르고, 마지막 후보가 {@code DISPATCHED} 로 남은 채 묶음이 영영
 * {@code RUNNING} 이 된다(S15P21E205-417). 사용자에게는 "어제 되던 자동 탐색이 오늘 멈춰 있다"로 보인다.
 *
 * <p>풀 크기에 기대지 않는 검사다 — 연쇄가 겹치면 후보 수와 무관하게 언젠가 걸리고, 겹치지 않으면 걸리지 않는다.
 */
class ParamSearchReuseChainIT extends PortMockedIntegrationTestSupport {

    private static final List<String> UNIVERSE = List.of("091160.XKRX", "305720.XKRX");

    @Autowired private ParamSearchService paramSearchService;
    @Autowired private ParamSearchRepository paramSearchRepository;
    @Autowired private SearchCandidateRepository candidateRepository;
    @Autowired private ParameterRegistryPort registry;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private TransactionTemplate transactionTemplate;

    private long requesterId;

    @BeforeEach
    void prepare() {
        jdbcTemplate.update("DELETE FROM search_candidate");
        // 내 기록이 이 행들을 가리키므로 먼저 지운다(spec 006 my_record FK).
        jdbcTemplate.update("DELETE FROM my_record");
        jdbcTemplate.update("DELETE FROM param_search");
        requesterId = newUser();
    }

    @Test
    @DisplayName("후보 전부가 끝난 실행을 재사용해도 묶음이 끝까지 간다")
    void reusedBundleDrainsToAFinalStatus() {
        List<String> targets = SearchableStrategies.excludingBaseline(registry, jdbcTemplate);
        assumeThat(targets).as("축이 있고 기준이 아닌 실행 가능 전략이 있어야 이 경로를 시험할 수 있다").isNotEmpty();

        // 첫 묶음을 손으로 밀어 실행을 전부 COMPLETED 로 만든다 — 두 번째 묶음의 재사용 대상이다.
        ParamSearchResult first = paramSearchService.create(command(targets));
        drive(first.searchId());

        // 두 번째 접수. 발행할 후보마다 완료가 그 자리에서 돌아오므로 이 호출 안에서 묶음이 끝난다.
        ParamSearchResult second = paramSearchService.create(command(targets));

        ParamSearch reloaded =
                paramSearchRepository.findBySearchId(second.searchId()).orElseThrow();
        assertThat(candidateRepository.findByParamSearchId(reloaded.getId()))
                .as("재사용 묶음은 대기도 실행 중도 남지 않는다 — 남으면 깨울 이벤트가 다시 오지 않는다")
                .noneMatch(
                        candidate ->
                                candidate.getStatus() == CandidateStatus.PENDING
                                        || candidate.getStatus() == CandidateStatus.DISPATCHED);
        assertThat(reloaded.getStatus().isFinal())
                .as("끝난 묶음은 최종 상태여야 한다: %s", reloaded.getStatus())
                .isTrue();
        assertThat(reloaded.getCounts().completed())
                .as("두 묶음이 같은 조합을 보므로 완료 수도 같다")
                .isEqualTo(
                        paramSearchRepository
                                .findBySearchId(first.searchId())
                                .orElseThrow()
                                .getCounts()
                                .completed());
    }

    /** 발행된 후보의 실행을 COMPLETED 로 바꾸고 결과 이벤트를 흘려 묶음을 끝까지 민다. */
    private void drive(String searchId) {
        for (int round = 0; round < 500; round++) {
            ParamSearch search = paramSearchRepository.findBySearchId(searchId).orElseThrow();
            List<SearchCandidate> dispatched =
                    candidateRepository.findByParamSearchIdAndStatus(
                            search.getId(), CandidateStatus.DISPATCHED);
            if (dispatched.isEmpty()) {
                return;
            }
            for (SearchCandidate candidate : dispatched) {
                long runId = candidate.getBacktestJobId();
                // 재사용 대상이 되려면 실행 행이 COMPLETED 여야 한다(spec 004 R-02).
                jdbcTemplate.update(
                        "UPDATE backtest_run SET status = 'COMPLETED', updated_at = NOW()"
                                + " WHERE id = ?",
                        runId);
                transactionTemplate.executeWithoutResult(
                        status ->
                                events.publishEvent(
                                        new BacktestFinished(
                                                runId,
                                                "BT-STUB-" + runId,
                                                RunStatus.COMPLETED,
                                                null)));
            }
        }
        throw new IllegalStateException("첫 묶음이 끝나지 않는다 — 두 번째 묶음을 볼 수 없다");
    }

    private CreateParamSearchCommand command(List<String> strategyIds) {
        return new CreateParamSearchCommand(
                requesterId, UNIVERSE, strategyIds, null, null, null, Map.of(), registry.version());
    }

    private long newUser() {
        String suffix = String.valueOf(System.nanoTime());
        String email = "reuse-" + suffix + "@example.com";
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
