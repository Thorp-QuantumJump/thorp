package com.ssafy.thorp.paramsearch.infrastructure.launcher;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.application.service.BacktestService;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.paramsearch.application.port.CandidateJobLauncher;
import com.ssafy.thorp.paramsearch.application.port.ParamSearchSnapshot;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 후보와 기준을 004의 접수 유스케이스로 보낸다(contracts/param-search-internal.md §2).
 *
 * <p>004가 같은 조건의 요청을 진행 중 실행에 합류시키거나 완료 결과를 재사용할 수 있다(spec 004 R-02). 그래도 007은 실행 행의 id를 받으므로 후보가
 * 결과를 찾는 경로는 달라지지 않는다 — 재사용은 004의 판단이고 007이 관여하지 않는다.
 *
 * <p><b>이 클래스에 전략 지식이 없다.</b> 후보는 자기 전략과 파라미터를 들고 오고, 기준 전략과 그 파라미터는 스냅샷에 담겨 있다(FR-025).
 */
@Component
public class CandidateJobLauncherImpl implements CandidateJobLauncher {

    private final BacktestService backtestService;
    private final BacktestRunRepository runRepository;

    public CandidateJobLauncherImpl(
            BacktestService backtestService, BacktestRunRepository runRepository) {
        this.backtestService = backtestService;
        this.runRepository = runRepository;
    }

    @Override
    public long launchCandidate(ParamSearchSnapshot snapshot, SearchCandidate candidate) {
        return launch(snapshot, candidate.getStrategyId(), candidate.getParameters().values());
    }

    @Override
    public long launchBaseline(ParamSearchSnapshot snapshot) {
        if (snapshot.baselineStrategyId() == null) {
            throw new IllegalStateException(
                    "baseline strategy is not registered: searchId=" + snapshot.searchId());
        }
        return launch(snapshot, snapshot.baselineStrategyId(), snapshot.baselineParameters());
    }

    private long launch(
            ParamSearchSnapshot snapshot, String strategyId, Map<String, Object> parameters) {
        BacktestJobResult result =
                backtestService.create(command(snapshot, strategyId, parameters));
        // 후보는 실행 행의 PK로 자기 결과를 찾는다(search_candidate.backtest_job_id). 접수 결과가
        // 돌려주는 것은 요청 식별자라 여기서 실행 행을 한 번 읽는다.
        return runRepository
                .findByBacktestId(result.backtestId())
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "accepted backtest has no run row: " + result.backtestId()))
                .getId();
    }

    private static CreateBacktestCommand command(
            ParamSearchSnapshot snapshot, String strategyId, Map<String, Object> parameters) {
        List<CreateBacktestCommand.AllocationItem> items =
                snapshot.universe().stream()
                        .map(
                                entry ->
                                        new CreateBacktestCommand.AllocationItem(
                                                entry.sectorId(),
                                                entry.instrumentId(),
                                                entry.targetWeight()))
                        .toList();
        return new CreateBacktestCommand(
                snapshot.ownerUserId(),
                // 묶음의 일부임을 004가 알아야 실행 한도와 측정이 단건과 섞이지 않는다.
                ExecutionType.SEARCH_BUNDLE,
                snapshot.mappingVersion(),
                items,
                strategyId,
                snapshot.rulesVersionOf(strategyId),
                snapshot.registryVersion(),
                parameters,
                snapshot.start(),
                snapshot.end(),
                snapshot.initialCash(),
                snapshot.costProfileVersion(),
                snapshot.evaluationSplitVersion(),
                // 묶음 공통 워밍업. 004는 이 값이 전략 요구값보다 작으면 422로 거부하므로
                // 스냅샷 계산이 틀리면 묶음이 시작 단계에서 드러난다(004 R-15).
                snapshot.warmupTradingDays(),
                null,
                null);
    }
}
