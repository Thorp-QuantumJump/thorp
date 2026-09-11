package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.port.message.EngineInput;
import com.ssafy.thorp.backtest.application.port.message.PositionSizingType;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import org.springframework.stereotype.Component;

/**
 * 저장된 Job을 작업 메시지 본문(v2)으로 조립한다. 사용자 입력은 Job에서, 고정 정책은 {@link ExecutionPolicy}에서 온다.
 *
 * <p>조립은 정책을 아는 application의 일이고 전송은 infrastructure의 일이라 발행 포트는 완성된 {@link EngineInput}만 받는다.
 *
 * <p>요청/실행 분리(spec 004 T003) 전이므로 {@code run.runId}에는 backtestId 값을 넣는다. 섹터 대응표·규칙 등록표 연동(T005)
 * 전이므로 {@code universe.mappingVersion}·{@code items[].sectorId}·{@code allocation.rules}는 비운다. Job의
 * 벤치마크 종목은 v2 작업 메시지에 없다(벤치마크 산출은 계약에서 제외됐다).
 */
@Component
public class EngineInputAssembler {

    public EngineInput assemble(BacktestRun job) {
        if (job.getBacktestId() == null) {
            throw new IllegalStateException("backtestId must be assigned before publishing");
        }
        return new EngineInput(
                new EngineInput.RunSpec(
                        job.getBacktestId().value(),
                        job.getPeriod().startDate(),
                        job.getPeriod().endDate(),
                        null,
                        job.getRandomSeed(),
                        ExecutionPolicy.SCENARIO,
                        null,
                        null),
                new EngineInput.UniverseSpec(
                        null,
                        job.getInstrumentIds().stream()
                                .map(id -> new EngineInput.UniverseItem(null, id.value(), null))
                                .toList()),
                ExecutionPolicy.MARKET_DATA,
                new EngineInput.AllocationSpec(
                        job.getStrategyId(), job.getStrategyVersion(), null, job.getParameters()),
                new EngineInput.PortfolioSpec(
                        job.getInitialCash(),
                        ExecutionPolicy.CURRENCY,
                        new EngineInput.PositionSizing(toMessageType(job), job.getMaxPositionPct()),
                        ExecutionPolicy.SHORT_SELLING,
                        ExecutionPolicy.LEVERAGE,
                        ExecutionPolicy.INTEGER_LOTS),
                ExecutionPolicy.EXECUTION,
                ExecutionPolicy.EVALUATION,
                ExecutionPolicy.PROGRESS);
    }

    /** 도메인 enum을 메시지 계약 enum으로 옮긴다. 도메인은 계층 규칙상 메시지 enum을 참조할 수 없다. */
    private static PositionSizingType toMessageType(BacktestRun job) {
        return switch (job.getPositionSizingType()) {
            case STRATEGY_DEFINED -> PositionSizingType.STRATEGY_DEFINED;
        };
    }
}
