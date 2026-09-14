package com.ssafy.thorp.paramsearch.infrastructure.support;

import com.ssafy.thorp.backtest.application.repository.BacktestResultRepository;
import com.ssafy.thorp.backtest.application.result.ResultPerformance;
import com.ssafy.thorp.paramsearch.application.port.CandidatePerformance;
import com.ssafy.thorp.paramsearch.application.port.CandidatePerformancePort;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 004의 결과 저장소에서 성과 숫자 셋을 읽어 온다.
 *
 * <p>이 어댑터가 {@code paramsearch}에 있는 이유는 {@code StrategyExecutionSupportAdapter}와 같다 — 반대 방향이면
 * {@code backtest}가 007의 포트를 알게 되어 "backtest는 paramsearch를 참조하지 않는다"는 계약이 깨진다
 * (contracts/param-search-internal.md §3).
 */
@Component
public class CandidatePerformanceAdapter implements CandidatePerformancePort {

    private final BacktestResultRepository resultRepository;

    public CandidatePerformanceAdapter(BacktestResultRepository resultRepository) {
        this.resultRepository = resultRepository;
    }

    @Override
    public Map<Long, CandidatePerformance> findByRunIds(Collection<Long> runIds) {
        Map<Long, CandidatePerformance> performances = new LinkedHashMap<>();
        resultRepository
                .findPerformanceByRunIds(runIds)
                .forEach(
                        (runId, performance) ->
                                performances.put(runId, toCandidatePerformance(performance)));
        return performances;
    }

    private static CandidatePerformance toCandidatePerformance(ResultPerformance performance) {
        return new CandidatePerformance(
                performance.netReturn(), performance.maxDrawdown(), performance.tradingCost());
    }
}
