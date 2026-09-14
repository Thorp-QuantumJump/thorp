package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 살아 있지 않은 실행을 거두는 규칙(R-19). 브로커는 "일하는 중"과 "멈춘 것"을 구분하지 못하므로 백엔드가 판단한다.
 *
 * <p>트랜잭션 관리자는 그냥 실행하는 가짜다 — 여기서 보려는 것은 "무엇을 언제 하느냐"이고, 건당 트랜잭션이 실제로 열리는지는 {@code
 * ExecutionLimitApiIT} 가 실제 DB 로 본다.
 */
class RunLivenessSweeperTest {

    private static final Instant NOW = Instant.parse("2026-09-14T05:00:00Z");
    private static final Duration MAX_RUN = Duration.ofSeconds(1800);
    private static final Duration STALE = Duration.ofSeconds(300);
    private static final Duration GRACE = Duration.ofSeconds(120);

    private final BacktestRunRepository runRepository = mock(BacktestRunRepository.class);
    private final BacktestCancelDispatcher dispatcher = mock(BacktestCancelDispatcher.class);

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
                        new ExecutionLimits(5, 50, MAX_RUN, STALE, GRACE, true),
                        transactionManager,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        50);
        when(runRepository.findOverrunning(any(), anyInt())).thenReturn(List.of());
        when(runRepository.findStaleRunning(any(), anyInt())).thenReturn(List.of());
        when(runRepository.findCancelUnanswered(any(), anyInt())).thenReturn(List.of());
    }

    private static BacktestRun run(RunStatus status, RunLifecycle lifecycle) {
        return BacktestRun.restore(
                11L,
                BacktestId.from("BT-20260914-000011"),
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

    private static RunLifecycle lifecycle() {
        return RunLifecycle.queuedAt(NOW.minusSeconds(3600));
    }

    @Test
    @DisplayName("실행 시간 상한을 넘긴 실행에는 EXECUTION_LIMIT 으로 CANCEL 을 보낸다")
    void cancelsAnOverrunningRun() {
        when(runRepository.findOverrunning(NOW.minus(MAX_RUN), 50))
                .thenReturn(List.of(run(RunStatus.RUNNING, lifecycle())));

        sweeper.sweep();

        verify(runRepository).recordCancelRequested(11L, NOW, CancelReason.EXECUTION_LIMIT);
        verify(dispatcher).publishAfterCommit("BT-20260914-000011");
    }

    @Test
    @DisplayName("마지막 이벤트로부터 조용한 실행에는 STALE 로 CANCEL 을 보낸다")
    void cancelsAStaleRun() {
        when(runRepository.findStaleRunning(NOW.minus(STALE), 50))
                .thenReturn(List.of(run(RunStatus.RUNNING, lifecycle())));

        sweeper.sweep();

        verify(runRepository).recordCancelRequested(11L, NOW, CancelReason.STALE);
        verify(dispatcher).publishAfterCommit("BT-20260914-000011");
    }

    @Test
    @DisplayName("취소에 응답하지 않으면 FAILED 로 강제 종결하고 결과 행을 만들지 않는다")
    void forceFinishesAnUnansweredCancel() {
        RunLifecycle cancelled =
                lifecycle().cancelRequested(NOW.minusSeconds(300), CancelReason.USER);
        when(runRepository.findCancelUnanswered(NOW.minus(GRACE), 50))
                .thenReturn(List.of(run(RunStatus.RUNNING, cancelled)));

        sweeper.sweep();

        ArgumentCaptor<BacktestRun> captor = ArgumentCaptor.forClass(BacktestRun.class);
        verify(runRepository).update(captor.capture());
        BacktestRun finished = captor.getValue();
        assertThat(finished.getStatus()).isEqualTo(RunStatus.FAILED);
        // 부분 결과가 없는데 부분 결과를 준다고 하지 않기 위해 CANCELLED 가 아니라 FAILED 다.
        assertThat(finished.isPartial()).isFalse();
        assertThat(finished.getFailureCode()).isEqualTo("SYSTEM_FAILURE");
        assertThat(finished.getLifecycle().finishedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("실행 시간 초과로 보낸 취소가 무응답이면 실패 사유가 EXECUTION_LIMIT 이다 — 사유를 다시 추론하지 않는다")
    void keepsTheOriginalReasonWhenForceFinishing() {
        RunLifecycle cancelled =
                lifecycle().cancelRequested(NOW.minusSeconds(300), CancelReason.EXECUTION_LIMIT);
        when(runRepository.findCancelUnanswered(NOW.minus(GRACE), 50))
                .thenReturn(List.of(run(RunStatus.RUNNING, cancelled)));

        sweeper.sweep();

        ArgumentCaptor<BacktestRun> captor = ArgumentCaptor.forClass(BacktestRun.class);
        verify(runRepository).update(captor.capture());
        assertThat(captor.getValue().getFailureCode()).isEqualTo("EXECUTION_LIMIT");
    }

    @Test
    @DisplayName("거둘 것이 없으면 아무것도 하지 않는다")
    void doesNothingWhenEverythingIsHealthy() {
        sweeper.sweep();

        verify(runRepository, never()).recordCancelRequested(any(), any(), any());
        verify(runRepository, never()).update(any());
        verify(dispatcher, never()).publishAfterCommit(eq("BT-20260914-000011"));
    }

    @Test
    @DisplayName("한 건이 터져도 나머지를 거둔다 — 한 주기가 통째로 멈추면 밀린 실행이 계속 쌓인다")
    void keepsGoingAfterOneFailure() {
        BacktestRun first = run(RunStatus.RUNNING, lifecycle());
        BacktestRun second =
                BacktestRun.restore(
                        12L,
                        BacktestId.from("BT-20260914-000012"),
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
                        RunState.of(RunStatus.RUNNING),
                        RunPlan.empty(),
                        lifecycle());
        when(runRepository.findStaleRunning(NOW.minus(STALE), 50))
                .thenReturn(List.of(first, second));
        org.mockito.Mockito.doThrow(new IllegalStateException("db hiccup"))
                .when(runRepository)
                .recordCancelRequested(eq(11L), any(), any());

        sweeper.sweep();

        verify(runRepository).recordCancelRequested(12L, NOW, CancelReason.STALE);
        verify(dispatcher).publishAfterCommit("BT-20260914-000012");
    }
}
