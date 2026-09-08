package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.port.BacktestJobPublisher;
import com.ssafy.thorp.backtest.application.repository.BacktestJobRepository;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.domain.exception.BacktestNotFoundException;
import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import java.util.List;
import org.springframework.stereotype.Service;

/** 백테스트 Job 생성·조회 유스케이스. 계산은 하지 않고 Job을 저장한 뒤 Worker에 발행한다. */
@Service
public class BacktestService {

    private final BacktestJobRepository repository;
    private final BacktestJobPublisher publisher;

    public BacktestService(BacktestJobRepository repository, BacktestJobPublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    // TODO: 트랜잭션 경계와 publish 실패 처리는 RabbitMQ 발행기 구현에서 확정한다.
    public BacktestJobResult create(CreateBacktestCommand command) {
        BacktestJob job =
                BacktestJob.create(
                        command.strategyId(),
                        ExecutionPolicy.STRATEGY_VERSION,
                        command.parameters(),
                        toInstrumentIds(command.instrumentIds()),
                        new BacktestPeriod(command.start(), command.end()),
                        command.initialCash(),
                        command.positionSizingType(),
                        command.maxPositionPct(),
                        toInstrumentId(command.benchmarkInstrumentId()),
                        ExecutionPolicy.DEFAULT_RANDOM_SEED);
        BacktestJob saved = repository.save(job);
        publisher.publish(saved);
        return BacktestJobResult.from(saved);
    }

    public BacktestJobResult get(String backtestId) {
        return repository
                .findByBacktestId(backtestId)
                .map(BacktestJobResult::from)
                .orElseThrow(() -> new BacktestNotFoundException(backtestId));
    }

    private static List<InstrumentId> toInstrumentIds(List<String> values) {
        return values == null ? List.of() : values.stream().map(InstrumentId::new).toList();
    }

    private static InstrumentId toInstrumentId(String value) {
        return value == null ? null : new InstrumentId(value);
    }
}
