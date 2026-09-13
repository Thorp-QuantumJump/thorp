package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.port.BacktestControlPublisher;
import com.ssafy.thorp.backtest.application.repository.BacktestRequestRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.application.result.BacktestCancelResult;
import com.ssafy.thorp.backtest.domain.exception.BacktestNotFoundException;
import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 취소 유스케이스(spec 004 R-04, T011). <b>이 요청만</b> 취소하고, 같은 실행의 마지막 활성 요청일 때만 실행을 멈춘다.
 *
 * <ul>
 *   <li>합류 요청 중 하나만 취소: 요청에 {@code cancelled_at}과 스냅샷({@code run.progress_last_trading_date}, 없으면
 *       NULL = 완료한 거래일 없음)을 남긴다. 실행은 계속 돌고 이 요청의 표시 범위는 스냅샷을 넘지 않는다
 *   <li>마지막 활성 요청의 취소: {@code stopped_run=true}이고 스냅샷은 남기지 않는다 — 범위는 FINISHED(CANCELLED)의 {@code
 *       lastTradingDate}가 실행에 남는다(워커는 마지막 PROGRESS 뒤 며칠을 더 계산한 뒤 멈춘다). 실행이 QUEUED 면 워커가 아직 없으니 바로
 *       CANCELLED 로 끝내고, RUNNING 이면 {@code backtest.control}에 CANCEL 을 보낸다. 그 실행의 STARTED 가 뒤늦게 오면
 *       이벤트 처리가 CANCEL 을 다시 보낸다
 * </ul>
 *
 * <p>CANCEL 발행은 트랜잭션 안에서 한다. 실패하면 503 으로 되돌린다 — 요청만 취소로 남고 워커가 계속 돌면 유예 시간 뒤 강제 종결(T014)까지 아무도 모르는
 * 상태가 된다. 워커가 커밋보다 먼저 CANCEL 을 받는 것은 해롭지 않다(멈추고 FINISHED 를 보내면 그때 처리된다).
 */
@Service
public class BacktestCancelService {

    private static final Logger log = LoggerFactory.getLogger(BacktestCancelService.class);

    private final BacktestRunRepository runRepository;
    private final BacktestRequestRepository requestRepository;
    private final BacktestControlPublisher controlPublisher;
    private final Clock clock;

    public BacktestCancelService(
            BacktestRunRepository runRepository,
            BacktestRequestRepository requestRepository,
            BacktestControlPublisher controlPublisher,
            Clock clock) {
        this.runRepository = runRepository;
        this.requestRepository = requestRepository;
        this.controlPublisher = controlPublisher;
        this.clock = clock;
    }

    @Transactional
    public BacktestCancelResult cancel(String backtestId, long requesterId) {
        BacktestRequest request =
                requestRepository
                        .findByBacktestId(backtestId)
                        .filter(found -> found.getRequesterId() == requesterId)
                        .orElseThrow(() -> new BacktestNotFoundException(backtestId));
        BacktestRun run =
                runRepository
                        .findById(request.getRunId())
                        .orElseThrow(() -> new BacktestNotFoundException(backtestId));
        if (request.isCancelled() || run.getStatus().isFinal()) {
            throw new BusinessException(
                    ErrorCode.NOT_CANCELLABLE,
                    "이미 끝났거나 취소된 분석입니다: backtestId=" + backtestId + ", status=" + run.getStatus());
        }

        Instant now = clock.instant();
        // 이 요청도 아직 활성으로 세어져 있다. 남는 활성 요청이 없을 때만 실행을 멈춘다.
        boolean stopsRun = requestRepository.countActiveByRunId(run.getId()) <= 1;
        LocalDate snapshot = run.getProgress() == null ? null : run.getProgress().lastTradingDate();
        requestRepository.update(request.cancel(now, snapshot, stopsRun));
        if (!stopsRun) {
            log.info(
                    "request cancelled, run continues for other requests: backtestId={} runId={}",
                    backtestId,
                    run.getBacktestId().value());
            return new BacktestCancelResult(backtestId, RunStatus.CANCELLED, false);
        }
        if (run.getStatus() == RunStatus.QUEUED) {
            // 워커가 아직 없다. 실행을 바로 끝낸다 — 뒤늦게 STARTED 가 오면 이벤트 처리가 CANCEL 을 보낸다.
            BacktestRun cancelled = run.markCancelled(null);
            runRepository.update(cancelled.withLifecycle(cancelled.getLifecycle().finished(now)));
            return new BacktestCancelResult(backtestId, RunStatus.CANCELLED, false);
        }
        controlPublisher.cancel(run.getBacktestId().value());
        log.info("CANCEL sent: backtestId={} runId={}", backtestId, run.getBacktestId().value());
        return new BacktestCancelResult(backtestId, RunStatus.CANCELLED, true);
    }
}
