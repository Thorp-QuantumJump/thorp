package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.repository.BacktestRequestRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.domain.exception.BacktestNotFoundException;
import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실패·취소한 요청을 같은 조건으로 다시 시도한다(spec 004 T010).
 *
 * <p><b>접수를 다시 탄다.</b> 실행 행을 복제하지 않고 저장해 둔 요청 사본({@code request_spec})을 접수 명령으로 되돌려 {@link
 * BacktestService#create}에 넘긴다. 그래야 그 사이 전략 등록표가 내려갔거나(422) 자료 버전이 움직인 것을 <b>지금 기준</b>으로 다시 판정하고,
 * 동일 버전 재사용·합류도 똑같이 받는다 — 원본이 실패한 뒤 다른 사람이 같은 조건을 이미 돌리고 있으면 새로 계산하지 않고 거기에 합류한다.
 *
 * <p>비용 조건 버전만은 원본 실행의 것을 그대로 쓴다. 재시도는 "같은 조건을 다시"이지 "지금 기본값으로 새로"가 아니다 — 기본 프로필이 그 사이 바뀌었는데 조용히
 * 따라가면 사용자가 비교하려던 두 결과의 비용 전제가 달라진다.
 *
 * <p>재시도 가능 여부는 <b>요청 기준 상태</b>로 본다(R-04 파생 상태). 실행이 아직 돌고 있어도 이 요청이 취소됐으면 그 사용자에게는 끝난 분석이다.
 */
@Service
public class BacktestRetryService {

    private static final Logger log = LoggerFactory.getLogger(BacktestRetryService.class);

    private final BacktestService backtestService;
    private final BacktestRequestRepository requestRepository;
    private final BacktestRunRepository runRepository;

    public BacktestRetryService(
            BacktestService backtestService,
            BacktestRequestRepository requestRepository,
            BacktestRunRepository runRepository) {
        this.backtestService = backtestService;
        this.requestRepository = requestRepository;
        this.runRepository = runRepository;
    }

    @Transactional
    public BacktestJobResult retry(String backtestId, long requesterId) {
        // 남의 요청은 403 이 아니라 404 로 숨긴다(R-12) — 403 은 그 식별자가 존재한다고 알려 준다.
        BacktestRequest request =
                requestRepository
                        .findByBacktestId(backtestId)
                        .filter(found -> found.isOwnedBy(requesterId))
                        .orElseThrow(() -> new BacktestNotFoundException(backtestId));
        BacktestRun run =
                runRepository
                        .findById(request.getRunId())
                        .orElseThrow(() -> new BacktestNotFoundException(backtestId));

        RunStatus status = request.isCancelled() ? RunStatus.CANCELLED : run.getStatus();
        if (status != RunStatus.FAILED && status != RunStatus.CANCELLED) {
            throw new BusinessException(
                    ErrorCode.NOT_RETRYABLE,
                    "실패하거나 취소한 분석만 다시 시도할 수 있습니다: backtestId=" + backtestId + ", status=" + status);
        }

        CreateBacktestCommand command =
                RequestSpecCodec.toCommand(
                        run.getPlan().requestSpec(),
                        requesterId,
                        request.getExecutionType(),
                        run.getPlan().costProfileVersion());
        BacktestJobResult retried = backtestService.create(command, request.getId());
        log.info(
                "retry accepted: original={} retried={} resultSource={}",
                backtestId,
                retried.backtestId(),
                retried.resultSource());
        return retried.withRetryOf(backtestId);
    }
}
