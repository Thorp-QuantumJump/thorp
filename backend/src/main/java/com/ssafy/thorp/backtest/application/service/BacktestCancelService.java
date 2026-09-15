package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.repository.BacktestRequestRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.application.result.BacktestCancelResult;
import com.ssafy.thorp.backtest.domain.exception.BacktestNotFoundException;
import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.CancelReason;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
 * <p>취소 의도는 {@code run.cancel_requested_at} 에 커밋되고 CANCEL 은 커밋 뒤 {@link BacktestCancelDispatcher} 가
 * 보낸다(작업 발행과 같은 방식). 트랜잭션 안에서 보내면 발행됐는데 커밋이 실패할 수 있고, 그러면 아무도 취소하지 않은 실행이 멈춘다. 발행이 실패해도 의도가 남아 있어
 * 유예 감시(T014)가 다시 보낼 수 있다.
 *
 * <p>동시성: 요청 행과 실행 행을 <b>그 순서로</b> 잠근다. 요청 잠금은 같은 요청의 이중 취소를(뒤엣것이 앞엣것의 취소를 보아야 한다), 실행 잠금은 같은 실행의
 * 마지막 활성 요청 판정을 직렬화한다. 접수(합류)도 실행 행을 잠그고 새 요청 행을 넣을 뿐이라 잠금 순서가 어긋나 교착하지 않는다.
 */
@Service
public class BacktestCancelService {

    private static final Logger log = LoggerFactory.getLogger(BacktestCancelService.class);

    private final BacktestRunRepository runRepository;
    private final BacktestRequestRepository requestRepository;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public BacktestCancelService(
            BacktestRunRepository runRepository,
            BacktestRequestRepository requestRepository,
            ApplicationEventPublisher events,
            Clock clock) {
        this.runRepository = runRepository;
        this.requestRepository = requestRepository;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public BacktestCancelResult cancel(String backtestId, long requesterId) {
        // 요청 행을 잠근 채 읽는다 — 같은 요청의 동시 취소에서 뒤엣것이 앞엣것의 취소를 보아야 한다.
        // 잠금 전에 읽어 두면 옛 상태로 "아직 활성"이라 판단해 남은 요청이 있는데도 실행을 멈춘다.
        BacktestRequest request =
                requestRepository
                        .findByBacktestIdForUpdate(backtestId)
                        .filter(found -> found.getRequesterId() == requesterId)
                        .orElseThrow(() -> new BacktestNotFoundException(backtestId));
        // 이어서 실행 행을 잠근다(잠금 순서: 요청 → 실행) — 같은 실행의 서로 다른 요청이 동시에 취소하면
        // 마지막 활성 요청 판정이 어긋난다.
        BacktestRun run =
                runRepository
                        .findByIdForUpdate(request.getRunId())
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
        // 취소 의도는 커밋되는 컬럼에 남기고(접수가 합류를 막고, 유예 감시가 기다리는 근거), CANCEL 은 커밋 뒤 보낸다.
        runRepository.recordCancelRequested(run.getId(), now, CancelReason.USER);
        events.publishEvent(
                new BacktestCancelDispatcher.CancelRequested(run.getBacktestId().value()));
        log.info(
                "cancel requested for the run: backtestId={} runId={}",
                backtestId,
                run.getBacktestId().value());
        return new BacktestCancelResult(backtestId, RunStatus.CANCELLED, true);
    }
}
