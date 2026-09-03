package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.port.BacktestJobPublisher;
import com.ssafy.thorp.backtest.application.repository.BacktestJobRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestResultRepository;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.application.result.EquityCurveResult;
import com.ssafy.thorp.backtest.domain.exception.BacktestNotFoundException;
import com.ssafy.thorp.backtest.domain.exception.BacktestResultNotReadyException;
import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import com.ssafy.thorp.backtest.domain.model.BacktestStatus;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 백테스트 Job 생성·조회 유스케이스. 계산은 하지 않고 Job을 저장한 뒤 Worker에 발행하며, 결과는 Worker가 저장한 행을 읽어 조합만 한다.
 *
 * <p>상태별 조회 동작:
 *
 * <ul>
 *   <li>{@code get}: 어떤 상태든 200. COMPLETED이고 결과 행이 있으면 summary를 채우고, 그 외에는 summary null
 *   <li>{@code getTimeseries}: COMPLETED이고 결과 행이 있을 때만 시계열. 그 외에는 {@link
 *       BacktestResultNotReadyException}(409)
 * </ul>
 */
@Service
public class BacktestService {

    private static final Logger log = LoggerFactory.getLogger(BacktestService.class);

    private final BacktestJobRepository repository;
    private final BacktestResultRepository resultRepository;
    private final BacktestJobPublisher publisher;

    public BacktestService(
            BacktestJobRepository repository,
            BacktestResultRepository resultRepository,
            BacktestJobPublisher publisher) {
        this.repository = repository;
        this.resultRepository = resultRepository;
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

    @Transactional(readOnly = true)
    public BacktestJobResult get(Long backtestId) {
        BacktestJob job = findJob(backtestId);
        if (job.getStatus() != BacktestStatus.COMPLETED) {
            return BacktestJobResult.from(job);
        }
        return resultRepository
                .findByBacktestJobId(backtestId)
                .map(result -> BacktestJobResult.from(job, result))
                .orElseGet(
                        () -> {
                            log.warn("COMPLETED backtest has no result row yet: id={}", backtestId);
                            return BacktestJobResult.from(job);
                        });
    }

    @Transactional(readOnly = true)
    public EquityCurveResult getTimeseries(Long backtestId) {
        BacktestJob job = findJob(backtestId);
        if (job.getStatus() != BacktestStatus.COMPLETED) {
            throw new BacktestResultNotReadyException(backtestId, job.getStatus());
        }
        return resultRepository
                .findByBacktestJobId(backtestId)
                .map(EquityCurveResult::from)
                .orElseThrow(
                        () -> {
                            log.warn("COMPLETED backtest has no result row yet: id={}", backtestId);
                            return new BacktestResultNotReadyException(backtestId, job.getStatus());
                        });
    }

    private BacktestJob findJob(Long backtestId) {
        return repository
                .findById(backtestId)
                .orElseThrow(() -> new BacktestNotFoundException(backtestId));
    }
}
