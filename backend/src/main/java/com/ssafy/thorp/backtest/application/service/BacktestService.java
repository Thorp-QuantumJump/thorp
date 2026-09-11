package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.port.BacktestJobPublisher;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.domain.exception.BacktestNotFoundException;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 백테스트 Job 생성·조회 유스케이스. 계산은 하지 않고 Job을 저장한 뒤 Worker에 발행한다.
 *
 * <p>생성은 저장과 발행을 한 트랜잭션으로 묶는다. 발행이 동기 오류로 실패하면 저장을 롤백해 큐에 들어가지 못한 Job이 QUEUED로 남지 않게 한다. 반대 방향(발행
 * 성공 후 커밋 실패)은 막지 못하며 outbox 도입 전까지의 알려진 한계다. 결과 Consumer는 모르는 backtestId 이벤트를 경고 로그 후 버린다.
 */
@Service
public class BacktestService {

    private final BacktestRunRepository repository;
    private final BacktestJobPublisher publisher;
    private final EngineInputAssembler assembler;

    public BacktestService(
            BacktestRunRepository repository,
            BacktestJobPublisher publisher,
            EngineInputAssembler assembler) {
        this.repository = repository;
        this.publisher = publisher;
        this.assembler = assembler;
    }

    @Transactional
    public BacktestJobResult create(CreateBacktestCommand command) {
        BacktestRun job =
                BacktestRun.create(
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
        BacktestRun saved = repository.save(job);
        publisher.publish(assembler.assemble(saved));
        return BacktestJobResult.from(saved);
    }

    @Transactional(readOnly = true)
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
