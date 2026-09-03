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
import com.ssafy.thorp.backtest.domain.model.BacktestResult;
import com.ssafy.thorp.backtest.domain.model.BacktestStatus;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import java.util.Optional;
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
        return findResult(job)
                .map(result -> BacktestJobResult.from(job, result))
                .orElseGet(() -> BacktestJobResult.from(job));
    }

    @Transactional(readOnly = true)
    public EquityCurveResult getTimeseries(Long backtestId) {
        BacktestJob job = findJob(backtestId);
        return findResult(job)
                .map(EquityCurveResult::from)
                .orElseThrow(
                        () -> new BacktestResultNotReadyException(backtestId, job.getStatus()));
    }

    private BacktestJob findJob(Long backtestId) {
        return repository
                .findById(backtestId)
                .orElseThrow(() -> new BacktestNotFoundException(backtestId));
    }

    /**
     * COMPLETED이고 결과 행이 있을 때만 값이 있다. COMPLETED인데 행이 없으면 Worker 저장 실패 등 비정상이므로 warn을 남기고, 호출자는 "아직
     * 없음"과 같게 처리한다(research.md §5).
     */
    private Optional<BacktestResult> findResult(BacktestJob job) {
        if (job.getStatus() != BacktestStatus.COMPLETED) {
            return Optional.empty();
        }
        Optional<BacktestResult> result = resultRepository.findByBacktestJobId(job.getId());
        if (result.isEmpty()) {
            log.warn("COMPLETED backtest has no result row yet: id={}", job.getId());
        }
        return result;
    }
}
