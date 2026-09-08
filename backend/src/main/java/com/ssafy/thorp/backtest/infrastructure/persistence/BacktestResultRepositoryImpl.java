package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.application.command.SaveBacktestResultCommand;
import com.ssafy.thorp.backtest.application.repository.BacktestResultRepository;
import org.springframework.stereotype.Repository;

/**
 * 결과 행과 거래 행을 저장한다.
 *
 * <p>Job은 {@code getReferenceById} 프록시로 참조한다. 연관을 채우는 데 PK만 필요하므로 추가 SELECT가 없고, 없는 Job이면 flush 시점에
 * FK 위반으로 드러난다. 결과와 거래를 한 트랜잭션에 넣는 것은 호출자(서비스)의 책임이다.
 */
@Repository
public class BacktestResultRepositoryImpl implements BacktestResultRepository {

    private final BacktestJobJpaRepository jobRepository;
    private final BacktestResultJpaRepository resultRepository;
    private final BacktestTradeJpaRepository tradeRepository;

    public BacktestResultRepositoryImpl(
            BacktestJobJpaRepository jobRepository,
            BacktestResultJpaRepository resultRepository,
            BacktestTradeJpaRepository tradeRepository) {
        this.jobRepository = jobRepository;
        this.resultRepository = resultRepository;
        this.tradeRepository = tradeRepository;
    }

    @Override
    public void save(SaveBacktestResultCommand command) {
        BacktestJobJpaEntity job = jobRepository.getReferenceById(command.backtestJobId());
        resultRepository.save(BacktestResultJpaEntity.from(job, command));
        tradeRepository.saveAll(
                command.engineOutput().trades().stream()
                        .map(trade -> BacktestTradeJpaEntity.from(job, trade))
                        .toList());
    }
}
