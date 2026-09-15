package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.backtest.application.repository.BacktestRequestRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.application.result.BacktestCancelResult;
import com.ssafy.thorp.backtest.domain.exception.BacktestNotFoundException;
import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.CancelReason;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.model.ProgressStage;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.model.RunProgress;
import com.ssafy.thorp.backtest.domain.model.RunState;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.backtest.domain.vo.BacktestId;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/** 취소 유스케이스(spec 004 R-04, T011): 마지막 활성 요청 규칙과 스냅샷. */
@ExtendWith(MockitoExtension.class)
class BacktestCancelServiceTest {

    private static final String BACKTEST_ID = "BT-20260907-000001";
    private static final String RUN_ID = "BT-20260907-000001";
    private static final Long RUN_PK = 1L;
    private static final long REQUESTER = 7L;
    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");
    private static final LocalDate PROGRESS_DATE = LocalDate.parse("2025-03-25");

    @Mock private BacktestRunRepository runRepository;
    @Mock private BacktestRequestRepository requestRepository;
    @Mock private ApplicationEventPublisher events;

    private BacktestCancelService service;

    @BeforeEach
    void createService() {
        service =
                new BacktestCancelService(
                        runRepository, requestRepository, events, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static BacktestRequest request(long requesterId, Instant cancelledAt) {
        return BacktestRequest.restore(
                21L,
                BacktestId.from(BACKTEST_ID),
                RUN_PK,
                requesterId,
                ExecutionType.SINGLE,
                ResultSource.NEW,
                null,
                cancelledAt,
                null,
                false);
    }

    private static BacktestRun run(RunStatus status, LocalDate progressDate) {
        RunProgress progress =
                progressDate == null
                        ? null
                        : new RunProgress(ProgressStage.COMPUTING, progressDate, 60, 129);
        return BacktestRun.restore(
                RUN_PK,
                BacktestId.from(RUN_ID),
                "FIXED_WEIGHT",
                "1",
                Map.of("rebalance", "MONTH_END"),
                List.of(new InstrumentId("091160.XKRX")),
                new BacktestPeriod(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 6, 30)),
                new BigDecimal("10000000.00"),
                PositionSizingMode.STRATEGY_DEFINED,
                null,
                null,
                42,
                new RunState(
                        status,
                        status == RunStatus.QUEUED ? 0 : 1,
                        "EXEC-1",
                        false,
                        progress,
                        null,
                        null));
    }

    private void given(BacktestRun run, long activeRequests) {
        when(requestRepository.findByBacktestIdForUpdate(BACKTEST_ID))
                .thenReturn(Optional.of(request(REQUESTER, null)));
        when(runRepository.findByIdForUpdate(RUN_PK)).thenReturn(Optional.of(run));
        when(requestRepository.countActiveByRunId(RUN_PK)).thenReturn(activeRequests);
    }

    private BacktestRequest updatedRequest() {
        ArgumentCaptor<BacktestRequest> captor = ArgumentCaptor.forClass(BacktestRequest.class);
        verify(requestRepository).update(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("마지막 활성 요청이 RUNNING 실행을 취소하면 실행 행을 잠근 채 취소 의도를 기록하고 커밋 뒤 CANCEL 을 보내게 한다")
    void lastActiveRequestOnRunningRunSendsCancel() {
        given(run(RunStatus.RUNNING, PROGRESS_DATE), 1);

        BacktestCancelResult result = service.cancel(BACKTEST_ID, REQUESTER);

        assertThat(result.backtestId()).isEqualTo(BACKTEST_ID);
        assertThat(result.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(result.cancelRequested()).isTrue();
        BacktestRequest cancelled = updatedRequest();
        assertThat(cancelled.isCancelled()).isTrue();
        assertThat(cancelled.getCancelledAt()).isEqualTo(NOW);
        assertThat(cancelled.isStoppedRun()).isTrue();
        // 범위는 FINISHED(CANCELLED)가 run 에 남긴다 — 워커는 마지막 PROGRESS 뒤 며칠을 더 계산한다(R-04)
        assertThat(cancelled.getCancelledLastTradingDate()).isNull();
        // 취소 의도는 커밋되는 컬럼에 남기고, CANCEL 발행은 커밋 뒤 디스패처가 한다(작업 발행과 같은 이유)
        verify(runRepository).recordCancelRequested(RUN_PK, NOW, CancelReason.USER);
        verify(events).publishEvent(new BacktestCancelDispatcher.CancelRequested(RUN_ID));
        verify(runRepository, never()).update(any());
    }

    @Test
    @DisplayName("마지막 활성 요청이 QUEUED 실행을 취소하면 실행을 바로 CANCELLED 로 끝내고 CANCEL 은 보내지 않는다")
    void lastActiveRequestOnQueuedRunCancelsImmediately() {
        given(run(RunStatus.QUEUED, null), 1);

        BacktestCancelResult result = service.cancel(BACKTEST_ID, REQUESTER);

        assertThat(result.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(result.cancelRequested()).isFalse();
        ArgumentCaptor<BacktestRun> captor = ArgumentCaptor.forClass(BacktestRun.class);
        verify(runRepository).update(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(captor.getValue().getLifecycle().finishedAt()).isEqualTo(NOW);
        assertThat(updatedRequest().isStoppedRun()).isTrue();
        verify(runRepository, never()).recordCancelRequested(any(), any(), any());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("합류 요청 하나만 취소하면 그 요청에 진행 날짜를 스냅샷으로 남기고 실행은 계속 돈다")
    void joinedRequestCancelsOnlyItself() {
        given(run(RunStatus.RUNNING, PROGRESS_DATE), 2);

        BacktestCancelResult result = service.cancel(BACKTEST_ID, REQUESTER);

        assertThat(result.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(result.cancelRequested()).isFalse();
        BacktestRequest cancelled = updatedRequest();
        assertThat(cancelled.isStoppedRun()).isFalse();
        assertThat(cancelled.getCancelledLastTradingDate()).isEqualTo(PROGRESS_DATE);
        verify(runRepository, never()).update(any());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("첫 PROGRESS 전에 합류 요청을 취소하면 스냅샷은 NULL — 완료한 거래일 없음이다")
    void joinedRequestBeforeProgressHasNoSnapshot() {
        given(run(RunStatus.RUNNING, null), 2);

        service.cancel(BACKTEST_ID, REQUESTER);

        assertThat(updatedRequest().getCancelledLastTradingDate()).isNull();
    }

    @Test
    @DisplayName("이미 끝난 실행이나 이미 취소한 요청은 409 NOT_CANCELLABLE 이다")
    void finalOrAlreadyCancelledIsNotCancellable() {
        when(requestRepository.findByBacktestIdForUpdate(BACKTEST_ID))
                .thenReturn(Optional.of(request(REQUESTER, null)));
        when(runRepository.findByIdForUpdate(RUN_PK))
                .thenReturn(Optional.of(run(RunStatus.COMPLETED, PROGRESS_DATE)));
        assertThatThrownBy(() -> service.cancel(BACKTEST_ID, REQUESTER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_CANCELLABLE);

        when(requestRepository.findByBacktestIdForUpdate(BACKTEST_ID))
                .thenReturn(Optional.of(request(REQUESTER, NOW.minusSeconds(60))));
        when(runRepository.findByIdForUpdate(RUN_PK))
                .thenReturn(Optional.of(run(RunStatus.RUNNING, PROGRESS_DATE)));
        assertThatThrownBy(() -> service.cancel(BACKTEST_ID, REQUESTER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_CANCELLABLE);
        verify(requestRepository, never()).update(any());
    }

    @Test
    @DisplayName("요청은 실행 행을 잠그기 전이 아니라 자기 행을 잠근 채 읽는다 — 같은 요청의 동시 취소가 옛 상태를 보면 안 된다")
    void readsTheRequestUnderItsOwnLock() {
        given(run(RunStatus.RUNNING, PROGRESS_DATE), 2);

        service.cancel(BACKTEST_ID, REQUESTER);

        verify(requestRepository).findByBacktestIdForUpdate(BACKTEST_ID);
        verify(requestRepository, never()).findByBacktestId(any());
    }

    @Test
    @DisplayName("남의 요청은 404 로 숨긴다(R-12)")
    void othersRequestIsNotFound() {
        when(requestRepository.findByBacktestIdForUpdate(BACKTEST_ID))
                .thenReturn(Optional.of(request(99L, null)));

        assertThatThrownBy(() -> service.cancel(BACKTEST_ID, REQUESTER))
                .isInstanceOf(BacktestNotFoundException.class);
        verifyNoInteractions(events);
    }
}
