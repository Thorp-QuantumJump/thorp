package com.ssafy.thorp.backtest.domain.model;

import com.ssafy.thorp.backtest.domain.vo.BacktestId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 사용자가 낸 분석 요청 한 건(spec 004 Clarify Q2·Q3, research R-02).
 *
 * <p>요청과 실행({@link BacktestRun})은 다대일이다. 같은 조건의 요청이 여러 번 들어와도 계산은 한 번만 하고, 각 요청은 자기 {@code
 * backtestId}로 같은 실행을 바라본다. 그래서 취소·재시도 권한과 결과 출처는 실행이 아니라 여기에 붙는다.
 *
 * <p><b>취소는 요청 단위다.</b> 어느 요청자가 취소해도 남은 활성 요청이 있으면 계산은 계속된다. 마지막 활성 요청이 취소될 때만 실행을 멈춘다 (spec Edge
 * Cases). 그래서 취소한 요청은 그 시점까지의 범위만 자기 결과로 본다 — 실행이 뒤에 더 계산해도 그 범위는 늘어나지 않는다(R-04).
 *
 * <p>접수 유스케이스(T005)가 이 모델을 만들어 저장한다. T003은 스키마·모델만 둔다.
 */
public class BacktestRequest {

    private final Long id;
    private final BacktestId backtestId;
    private final Long runId;
    private final Long requesterId;
    private final ExecutionType executionType;
    private final ResultSource resultSource;
    private final Long retryOfRequestId;
    private final Instant cancelledAt;
    private final LocalDate cancelledLastTradingDate;
    private final boolean stoppedRun;

    private BacktestRequest(
            Long id,
            BacktestId backtestId,
            Long runId,
            Long requesterId,
            ExecutionType executionType,
            ResultSource resultSource,
            Long retryOfRequestId,
            Instant cancelledAt,
            LocalDate cancelledLastTradingDate,
            boolean stoppedRun) {
        this.id = id;
        this.backtestId = backtestId;
        this.runId = Objects.requireNonNull(runId, "runId must not be null");
        this.requesterId = Objects.requireNonNull(requesterId, "requesterId must not be null");
        this.executionType =
                Objects.requireNonNull(executionType, "executionType must not be null");
        this.resultSource = Objects.requireNonNull(resultSource, "resultSource must not be null");
        this.retryOfRequestId = retryOfRequestId;
        this.cancelledAt = cancelledAt;
        this.cancelledLastTradingDate = cancelledLastTradingDate;
        this.stoppedRun = stoppedRun;
        if (cancelledAt == null && (cancelledLastTradingDate != null || stoppedRun)) {
            throw new IllegalArgumentException(
                    "cancel details require cancelledAt: runId=" + runId);
        }
    }

    /** 새 요청. id·backtestId는 저장 전이라 null이다. */
    public static BacktestRequest create(
            Long runId,
            Long requesterId,
            ExecutionType executionType,
            ResultSource resultSource,
            Long retryOfRequestId) {
        return new BacktestRequest(
                null,
                null,
                runId,
                requesterId,
                executionType,
                resultSource,
                retryOfRequestId,
                null,
                null,
                false);
    }

    /** 저장소에서 읽어온 상태를 복원한다. */
    public static BacktestRequest restore(
            Long id,
            BacktestId backtestId,
            Long runId,
            Long requesterId,
            ExecutionType executionType,
            ResultSource resultSource,
            Long retryOfRequestId,
            Instant cancelledAt,
            LocalDate cancelledLastTradingDate,
            boolean stoppedRun) {
        return new BacktestRequest(
                id,
                backtestId,
                runId,
                requesterId,
                executionType,
                resultSource,
                retryOfRequestId,
                cancelledAt,
                cancelledLastTradingDate,
                stoppedRun);
    }

    /**
     * 이 요청을 취소한다.
     *
     * @param at 취소 시각
     * @param runLastTradingDate 취소 시점에 실행이 알린 마지막 거래일. null = 아직 계산 단계에 이르지 못했다(완료 범위 없음)
     * @param stoppedRun 이 취소가 마지막 활성 요청이라 실행을 멈췄는가. true면 완료 범위는 실행이 FINISHED로 확정한다
     */
    public BacktestRequest cancel(Instant at, LocalDate runLastTradingDate, boolean stoppedRun) {
        Objects.requireNonNull(at, "at must not be null");
        if (isCancelled()) {
            throw new IllegalStateException("already cancelled: backtestId=" + backtestId);
        }
        return new BacktestRequest(
                id,
                backtestId,
                runId,
                requesterId,
                executionType,
                resultSource,
                retryOfRequestId,
                at,
                stoppedRun ? null : runLastTradingDate,
                stoppedRun);
    }

    public boolean isCancelled() {
        return cancelledAt != null;
    }

    /**
     * 이 요청이 볼 수 있는 완료 범위.
     *
     * <p>실행을 멈춘 취소와 취소하지 않은 요청은 실행의 값을 그대로 쓴다. 먼저 취소해 실행이 계속 돈 요청만 자기 스냅샷에 고정되며, 스냅샷이 null이면 "완료한
     * 거래일 없음"이다 — 뒤에 실행이 계산한 범위로 대신하지 않는다(R-04).
     */
    public LocalDate completedRangeOf(LocalDate runLastTradingDate) {
        if (!isCancelled() || stoppedRun) {
            return runLastTradingDate;
        }
        return cancelledLastTradingDate;
    }

    public Long getId() {
        return id;
    }

    /** 사용자에게 보이는 식별자 {@code BT-yyyyMMdd-순번}. 저장 전에는 null이다. */
    public BacktestId getBacktestId() {
        return backtestId;
    }

    public Long getRunId() {
        return runId;
    }

    public Long getRequesterId() {
        return requesterId;
    }

    public ExecutionType getExecutionType() {
        return executionType;
    }

    public ResultSource getResultSource() {
        return resultSource;
    }

    /** null = 재시도가 아니다. */
    public Long getRetryOfRequestId() {
        return retryOfRequestId;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    /** null = 취소하지 않았거나 완료한 거래일이 없다. {@link #completedRangeOf(LocalDate)}로 읽는다. */
    public LocalDate getCancelledLastTradingDate() {
        return cancelledLastTradingDate;
    }

    /** true = 이 취소가 실행을 멈췄다. */
    public boolean isStoppedRun() {
        return stoppedRun;
    }
}
