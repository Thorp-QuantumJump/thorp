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
 *
 * <p><b>요청자가 없을 수 있다</b>(spec 005 B01). 로그인하지 않은 사용자도 분석을 요청하고, 결과는 모든 요청에 붙는 공개 토큰({@link
 * PublicResultToken})으로 연다. 비로그인 요청은 토큰 만료 시각이 반드시 있다 — 만료 없는 익명 결과는 정리 작업이 영영 지우지 못한다(005
 * data-model §1). 로그인 요청은 만료가 없다.
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
    private final PublicResultToken publicToken;
    private final Instant publicTokenExpiresAt;

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
            boolean stoppedRun,
            PublicResultToken publicToken,
            Instant publicTokenExpiresAt) {
        this.id = id;
        this.backtestId = backtestId;
        this.runId = Objects.requireNonNull(runId, "runId must not be null");
        this.requesterId = requesterId;
        this.executionType =
                Objects.requireNonNull(executionType, "executionType must not be null");
        this.resultSource = Objects.requireNonNull(resultSource, "resultSource must not be null");
        this.retryOfRequestId = retryOfRequestId;
        this.cancelledAt = cancelledAt;
        this.cancelledLastTradingDate = cancelledLastTradingDate;
        this.stoppedRun = stoppedRun;
        this.publicToken = Objects.requireNonNull(publicToken, "publicToken must not be null");
        this.publicTokenExpiresAt = publicTokenExpiresAt;
        if (requesterId == null && publicTokenExpiresAt == null) {
            throw new IllegalArgumentException(
                    "anonymous request requires publicTokenExpiresAt: runId=" + runId);
        }
        if (cancelledAt == null && (cancelledLastTradingDate != null || stoppedRun)) {
            throw new IllegalArgumentException(
                    "cancel details require cancelledAt: runId=" + runId);
        }
    }

    /**
     * 새 요청. id·backtestId는 저장 전이라 null이다.
     *
     * @param requesterId null = 비로그인 요청. 그때는 {@code publicTokenExpiresAt}이 필요하다
     * @param publicTokenExpiresAt null = 만료 없음(로그인 요청)
     */
    public static BacktestRequest create(
            Long runId,
            Long requesterId,
            ExecutionType executionType,
            ResultSource resultSource,
            Long retryOfRequestId,
            PublicResultToken publicToken,
            Instant publicTokenExpiresAt) {
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
                false,
                publicToken,
                publicTokenExpiresAt);
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
            boolean stoppedRun,
            PublicResultToken publicToken,
            Instant publicTokenExpiresAt) {
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
                stoppedRun,
                publicToken,
                publicTokenExpiresAt);
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
                stoppedRun,
                publicToken,
                publicTokenExpiresAt);
    }

    public boolean isCancelled() {
        return cancelledAt != null;
    }

    /** 로그인하지 않고 낸 요청인가. */
    public boolean isAnonymous() {
        return requesterId == null;
    }

    /**
     * 이 사람이 낸 요청인가. 비로그인 요청은 누구의 것도 아니다 — 소유자 경로({@code /backtests/**})로는 열리지 않고 공개 토큰으로만 본다.
     *
     * <p>{@code getRequesterId() == requesterId}로 비교하지 않는 이유: 요청자가 null이면 언박싱에서 NPE가 나 404 대신 500이
     * 된다.
     */
    public boolean isOwnedBy(long requesterId) {
        return this.requesterId != null && this.requesterId == requesterId;
    }

    /**
     * 공개 토큰이 만료됐는가. 만료 시각과 <b>같은 순간부터</b> 만료다. 로그인 요청은 만료가 없어 늘 false다.
     *
     * @param now 판정 시각
     */
    public boolean isPublicExpired(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return publicTokenExpiresAt != null && !now.isBefore(publicTokenExpiresAt);
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

    /** null = 비로그인 요청. 소유자 판정은 {@link #isOwnedBy(long)}로 한다. */
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

    public PublicResultToken getPublicToken() {
        return publicToken;
    }

    /** null = 만료 없음(로그인 요청). */
    public Instant getPublicTokenExpiresAt() {
        return publicTokenExpiresAt;
    }
}
