package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.port.message.EngineInput;
import com.ssafy.thorp.backtest.application.port.message.PositionSizingType;
import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import org.springframework.stereotype.Component;

/**
 * 저장된 Job을 작업 메시지 본문으로 조립한다. 사용자 입력은 Job에서, 고정 정책은 {@link ExecutionPolicy}에서 온다.
 *
 * <p>조립은 정책을 아는 application의 일이고 전송은 infrastructure의 일이라 발행 포트는 완성된 {@link EngineInput}만 받는다.
 */
@Component
public class EngineInputAssembler {

    public EngineInput assemble(BacktestJob job) {
        if (job.getBacktestId() == null) {
            throw new IllegalStateException("backtestId must be assigned before publishing");
        }
        return new EngineInput(
                new EngineInput.RunSpec(
                        job.getBacktestId().value(),
                        job.getPeriod().startDate(),
                        job.getPeriod().endDate(),
                        job.getRandomSeed()),
                new EngineInput.UniverseSpec(
                        job.getInstrumentIds().stream().map(InstrumentId::value).toList()),
                ExecutionPolicy.MARKET_DATA,
                new EngineInput.StrategySpec(
                        job.getStrategyId(), job.getStrategyVersion(), job.getParameters()),
                new EngineInput.PortfolioSpec(
                        job.getInitialCash(),
                        ExecutionPolicy.CURRENCY,
                        new EngineInput.PositionSizing(toMessageType(job), job.getMaxPositionPct()),
                        ExecutionPolicy.SHORT_SELLING,
                        ExecutionPolicy.LEVERAGE),
                ExecutionPolicy.EXECUTION,
                job.getBenchmarkInstrumentId() == null
                        ? null
                        : new EngineInput.BenchmarkSpec(job.getBenchmarkInstrumentId().value()));
    }

    /** 도메인 enum을 메시지 계약 enum으로 옮긴다. 도메인은 계층 규칙상 메시지 enum을 참조할 수 없다. */
    private static PositionSizingType toMessageType(BacktestJob job) {
        return switch (job.getPositionSizingType()) {
            case STRATEGY_DEFINED -> PositionSizingType.STRATEGY_DEFINED;
        };
    }
}
