package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.backtest.application.event.BacktestFinished;
import com.ssafy.thorp.backtest.application.port.ExecutionLimits;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.CancelReason;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.model.RunLifecycle;
import com.ssafy.thorp.backtest.domain.model.RunPlan;
import com.ssafy.thorp.backtest.domain.model.RunState;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.backtest.domain.vo.BacktestId;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 살아 있지 않은 실행을 거두는 규칙(R-19). 브로커는 "일하는 중"과 "멈춘 것"을 구분하지 못하므로 백엔드가 판단한다.
 *
 * <p><b>목록으로 읽은 것과 잠근 뒤 다시 읽은 것을 따로 준다.</b> 이 둘이 다를 수 있다는 것이 이 스위퍼의 핵심 위험이다 — 그 사이 워커가 응답하면 낡은 판단으로
 * 정상 종결을 덮게 된다.
 */
class RunLivenessSweeperTest {

    private static final Instant NOW = Instant.parse("2026-09-14T05:00:00Z");
    private static final Duration MAX_RUN = Duration.ofSeconds(1800);
    private static final Duration STALE = Duration.ofSeconds(300);
    private static final Duration GRACE = Duration.ofSeconds(120);
    private static final String RUN_ID = "BT-20260914-000011";

    private final BacktestRunRepository runRepository = mock(BacktestRunRepository.class);
    private final BacktestCancelDispatcher dispatcher = mock(BacktestCancelDispatcher.class);
    private final ExecutionMetricRecorder metrics = mock(ExecutionMetricRecorder.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private RunLivenessSweeper sweeper;

    @BeforeEach
    void createSweeper() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn((TransactionStatus) new SimpleTransactionStatus());
        sweeper =
                new RunLivenessSweeper(
                        runRepository,
                        dispatcher,
                        metrics,
                        new ExecutionLimits(5, 50, 10, MAX_RUN, STALE, GRACE, true),
                        events,
                        transactionManager,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        50);
        when(runRepository.findOverrunning(any(), anyInt())).thenReturn(List.of());
        when(runRepository.findStaleRunning(any(), anyInt())).thenReturn(List.of());
        when(runRepository.findCancelPending(anyInt())).thenReturn(List.of());
    }

    private static BacktestRun run(
            long id, String backtestId, RunStatus status, RunLifecycle lifecycle) {
        return BacktestRun.restore(
                id,
                BacktestId.from(backtestId),
                "FIXED_WEIGHT",
                "1",
                Map.of(),
                List.of(new InstrumentId("091160.XKRX")),
                new BacktestPeriod(LocalDate.of(2024, 1, 2), LocalDate.of(2024, 12, 31)),
                new BigDecimal("10000000.00"),
                PositionSizingMode.STRATEGY_DEFINED,
                null,
                null,
                42,
                RunState.of(status),
                RunPlan.empty(),
                lifecycle);
    }

    private static BacktestRun run(RunStatus status, RunLifecycle lifecycle) {
        return run(11L, RUN_ID, status, lifecycle);
    }

    /** 시작한 지 오래됐고 마지막 이벤트도 오래된 실행. 정체·실행 시간 초과 판정이 모두 선다. */
    private static RunLifecycle longRunning() {
        return RunLifecycle.queuedAt(NOW.minusSeconds(3600))
                .started(NOW.minusSeconds(3600))
                .eventReceived(NOW.minusSeconds(3600));
    }

    private static RunLifecycle cancelledAgo(long seconds, CancelReason reason) {
        return longRunning().cancelRequested(NOW.minusSeconds(seconds), reason);
    }

    /** 잠근 뒤 다시 읽었을 때 돌아올 실행. 목록에 나온 것과 다를 수 있다. */
    private void whenRelocked(BacktestRun locked) {
        when(runRepository.findByIdForUpdate(locked.getId())).thenReturn(Optional.of(locked));
    }

    private BacktestRun updatedRun() {
        ArgumentCaptor<BacktestRun> captor = ArgumentCaptor.forClass(BacktestRun.class);
        verify(runRepository).update(captor.capture());
        return captor.getValue();
    }

    // ---------------------------------------------------------------- 협력 취소를 보낸다

    @Test
    @DisplayName("실행 시간 상한을 넘긴 실행에는 EXECUTION_LIMIT 으로 CANCEL 을 보낸다")
    void cancelsAnOverrunningRun() {
        BacktestRun overrunning = run(RunStatus.RUNNING, longRunning());
        when(runRepository.findOverrunning(NOW.minus(MAX_RUN), 50))
                .thenReturn(List.of(overrunning));
        whenRelocked(overrunning);

        sweeper.sweep();

        verify(runRepository).recordCancelRequested(11L, NOW, CancelReason.EXECUTION_LIMIT);
        verify(dispatcher).publishAfterCommit(RUN_ID);
    }

    @Test
    @DisplayName("마지막 이벤트로부터 조용한 실행에는 STALE 로 CANCEL 을 보낸다")
    void cancelsAStaleRun() {
        BacktestRun stale = run(RunStatus.RUNNING, longRunning());
        when(runRepository.findStaleRunning(NOW.minus(STALE), 50)).thenReturn(List.of(stale));
        whenRelocked(stale);

        sweeper.sweep();

        verify(runRepository).recordCancelRequested(11L, NOW, CancelReason.STALE);
        verify(dispatcher).publishAfterCommit(RUN_ID);
    }

    // ---------------------------------------------------------------- 읽기와 쓰기 사이의 경합

    @Test
    @DisplayName("잠근 뒤 이미 끝난 실행에는 취소 의도를 남기지 않는다 — 남기면 동일 버전 접수가 영원히 막힌다")
    void doesNotMarkCancelOnARunThatFinishedMeanwhile() {
        when(runRepository.findStaleRunning(NOW.minus(STALE), 50))
                .thenReturn(List.of(run(RunStatus.RUNNING, longRunning())));
        // 잠그고 다시 읽으니 그 사이 COMPLETED 로 끝나 있었다.
        whenRelocked(run(RunStatus.COMPLETED, longRunning()));

        sweeper.sweep();

        verify(runRepository, never()).recordCancelRequested(any(), any(), any());
        verify(dispatcher, never()).publishAfterCommit(any());
    }

    @Test
    @DisplayName("잠근 뒤 조건이 더 이상 맞지 않으면 멈추지 않는다 — 그 사이 PROGRESS 가 왔을 수 있다")
    void doesNotCancelWhenTheRunCameBackToLife() {
        when(runRepository.findStaleRunning(NOW.minus(STALE), 50))
                .thenReturn(List.of(run(RunStatus.RUNNING, longRunning())));
        whenRelocked(run(RunStatus.RUNNING, longRunning().eventReceived(NOW.minusSeconds(1))));

        sweeper.sweep();

        verify(runRepository, never()).recordCancelRequested(any(), any(), any());
    }

    @Test
    @DisplayName("잠근 뒤 이미 끝나 있으면 정상 종결을 FAILED 로 덮지 않는다")
    void doesNotOverwriteARunThatFinishedMeanwhile() {
        when(runRepository.findCancelPending(50))
                .thenReturn(List.of(run(RunStatus.RUNNING, cancelledAgo(300, CancelReason.USER))));
        whenRelocked(run(RunStatus.COMPLETED, cancelledAgo(300, CancelReason.USER)));

        sweeper.sweep();

        verify(runRepository, never()).update(any());
        verify(events, never()).publishEvent(any());
    }

    // ---------------------------------------------------------------- 취소 정산

    @Test
    @DisplayName("유예 전이면 강제 종결 대신 CANCEL 을 다시 보낸다 — 발행이 실패했을 수 있다")
    void resendsCancelWhileWaiting() {
        BacktestRun pending = run(RunStatus.RUNNING, cancelledAgo(10, CancelReason.USER));
        when(runRepository.findCancelPending(50)).thenReturn(List.of(pending));
        whenRelocked(pending);

        sweeper.sweep();

        verify(dispatcher).publishAfterCommit(RUN_ID);
        verify(runRepository, never()).update(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("취소에 응답하지 않으면 FAILED 로 강제 종결하고 결과 행을 만들지 않는다")
    void forceFinishesAnUnansweredCancel() {
        BacktestRun pending = run(RunStatus.RUNNING, cancelledAgo(300, CancelReason.USER));
        when(runRepository.findCancelPending(50)).thenReturn(List.of(pending));
        whenRelocked(pending);

        sweeper.sweep();

        BacktestRun finished = updatedRun();
        assertThat(finished.getStatus()).isEqualTo(RunStatus.FAILED);
        // 부분 결과가 없는데 부분 결과를 준다고 하지 않기 위해 CANCELLED 가 아니라 FAILED 다.
        assertThat(finished.isPartial()).isFalse();
        assertThat(finished.getFailureCode()).isEqualTo("SYSTEM_FAILURE");
        assertThat(finished.getLifecycle().finishedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("실행 시간 초과로 보낸 취소가 무응답이면 실패 사유가 EXECUTION_LIMIT 이다 — 사유를 다시 추론하지 않는다")
    void keepsTheOriginalReasonWhenForceFinishing() {
        BacktestRun pending =
                run(RunStatus.RUNNING, cancelledAgo(300, CancelReason.EXECUTION_LIMIT));
        when(runRepository.findCancelPending(50)).thenReturn(List.of(pending));
        whenRelocked(pending);

        sweeper.sweep();

        assertThat(updatedRun().getFailureCode()).isEqualTo("EXECUTION_LIMIT");
    }

    @Test
    @DisplayName("강제 종결도 완료를 알린다 — 자동 탐색은 이 신호로 후보를 정산한다")
    void forceFinishPublishesCompletion() {
        BacktestRun pending = run(RunStatus.RUNNING, cancelledAgo(300, CancelReason.USER));
        when(runRepository.findCancelPending(50)).thenReturn(List.of(pending));
        whenRelocked(pending);

        sweeper.sweep();

        verify(events)
                .publishEvent(
                        new BacktestFinished(11L, RUN_ID, RunStatus.FAILED, "SYSTEM_FAILURE"));
    }

    // ---------------------------------------------------------------- 주기 자체

    @Test
    @DisplayName("거둘 것이 없으면 아무것도 하지 않는다")
    void doesNothingWhenEverythingIsHealthy() {
        sweeper.sweep();

        verify(runRepository, never()).recordCancelRequested(any(), any(), any());
        verify(runRepository, never()).update(any());
        verify(dispatcher, never()).publishAfterCommit(any());
    }

    @Test
    @DisplayName("한 건이 터져도 나머지를 거둔다 — 한 주기가 통째로 멈추면 밀린 실행이 계속 쌓인다")
    void keepsGoingAfterOneFailure() {
        BacktestRun first = run(RunStatus.RUNNING, longRunning());
        BacktestRun second = run(12L, "BT-20260914-000012", RunStatus.RUNNING, longRunning());
        when(runRepository.findStaleRunning(NOW.minus(STALE), 50))
                .thenReturn(List.of(first, second));
        whenRelocked(first);
        whenRelocked(second);
        doThrow(new IllegalStateException("db hiccup"))
                .when(runRepository)
                .recordCancelRequested(eq(11L), any(), any());

        sweeper.sweep();

        verify(runRepository).recordCancelRequested(12L, NOW, CancelReason.STALE);
        verify(dispatcher).publishAfterCommit("BT-20260914-000012");
    }
}
