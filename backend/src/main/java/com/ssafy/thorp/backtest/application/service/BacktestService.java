package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.port.BacktestJobPublisher;
import com.ssafy.thorp.backtest.application.repository.BacktestJobRepository;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.domain.exception.BacktestNotFoundException;
import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
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

    // TODO: 트랜잭션 경계와 publish 실패 처리(저장 롤백 vs FAILED 마킹 vs outbox)는 브로커 결정 후 확정한다.
    public BacktestJobResult create(CreateBacktestCommand command) {
        BacktestJob job =
                BacktestJob.create(
                        command.strategy(),
                        new BacktestPeriod(command.startDate(), command.endDate()),
                        command.initialCash());
        BacktestJob saved = repository.save(job);
        publisher.publish(saved);
        return BacktestJobResult.from(saved);
    }

    public BacktestJobResult get(Long backtestId) {
        return repository
                .findById(backtestId)
                .map(BacktestJobResult::from)
                .orElseThrow(() -> new BacktestNotFoundException(backtestId));
    }
}
