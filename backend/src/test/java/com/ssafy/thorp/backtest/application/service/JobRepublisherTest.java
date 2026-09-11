package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.backtest.application.port.BacktestJobPublisher;
import com.ssafy.thorp.backtest.application.port.message.EngineInput;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.model.RunLifecycle;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 커밋됐는데 브로커에 닿지 못한 작업의 재발행(spec 004 research R-18).
 *
 * <p>순서가 이 기능의 전부다. <b>시도 횟수를 먼저 올리고 커밋한 뒤 보낸다.</b> 뒤집으면 그 사이 죽었을 때 브로커에는 메시지가 있는데 시도 횟수가 모자라고, 그러면
 * DLQ 종결 판정({@code dlqCount >= publishCount})이 아직 살아 있는 실행을 실패로 끊는다.
 */
class JobRepublisherTest {

    private static final Instant NOW = Instant.parse("2026-09-11T06:00:00Z");

    private final BacktestRunRepository repository = mock(BacktestRunRepository.class);
    private final BacktestJobPublisher publisher = mock(BacktestJobPublisher.class);
    private final EngineInputAssembler assembler = mock(EngineInputAssembler.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    /** 트랜잭션 경계는 이 테스트의 관심사가 아니다 — 콜백을 그대로 실행한다. */
    private final TransactionTemplate transactionTemplate =
            new TransactionTemplate() {
                @Override
                public <T> T execute(TransactionCallback<T> action) {
                    return action.doInTransaction(new SimpleTransactionStatus());
                }
            };

    private final BacktestJobDispatcher dispatcher =
            new BacktestJobDispatcher(repository, publisher, assembler, transactionTemplate, clock);

    private final JobRepublisher republisher =
            new JobRepublisher(repository, dispatcher, clock, Duration.ofMinutes(1), 50);

    private static BacktestRun queuedRun(int publishCount, Instant publishedAt) {
        return BacktestRun.restore(
                11L,
                BacktestId.from("BT-20260911-000011"),
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
                com.ssafy.thorp.backtest.domain.model.RunState.of(RunStatus.QUEUED),
                com.ssafy.thorp.backtest.domain.model.RunPlan.empty(),
                new RunLifecycle(NOW.minusSeconds(600), null, null, publishedAt, publishCount, 0));
    }

    @Test
    @DisplayName("발행 확인이 없는 대기 작업을 다시 보내고 시도 횟수를 올린다")
    void republishesUnpublished() {
        BacktestRun stale = queuedRun(1, null);
        when(repository.findUnpublishedQueued(any(), anyInt())).thenReturn(List.of(stale));
        when(repository.findById(11L)).thenReturn(Optional.of(stale));
        when(repository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(assembler.assemble(any())).thenReturn(mock(EngineInput.class));

        republisher.republishUnpublished();

        ArgumentCaptor<BacktestRun> captor = ArgumentCaptor.forClass(BacktestRun.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).update(captor.capture());
        assertThat(captor.getAllValues().get(0).getLifecycle().publishCount())
                .as("보내기 전에 먼저 올린다")
                .isEqualTo(2);
        verify(publisher).publish(any());
    }

    @Test
    @DisplayName("재발행이 성공하면 발행 확인 시각을 남긴다 — 다음 주기에 다시 집어 가지 않는다")
    void marksPublishedOnSuccess() {
        BacktestRun stale = queuedRun(1, null);
        when(repository.findUnpublishedQueued(any(), anyInt())).thenReturn(List.of(stale));
        when(repository.findById(11L)).thenReturn(Optional.of(stale));
        when(repository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(assembler.assemble(any())).thenReturn(mock(EngineInput.class));

        republisher.republishUnpublished();

        ArgumentCaptor<BacktestRun> captor = ArgumentCaptor.forClass(BacktestRun.class);
        verify(repository, org.mockito.Mockito.atLeast(2)).update(captor.capture());
        assertThat(captor.getAllValues().getLast().getLifecycle().publishedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("재발행이 또 실패하면 발행 확인을 남기지 않는다 — 다음 주기가 다시 집어 간다")
    void keepsUnpublishedOnFailure() {
        BacktestRun stale = queuedRun(1, null);
        when(repository.findUnpublishedQueued(any(), anyInt())).thenReturn(List.of(stale));
        when(repository.findById(11L)).thenReturn(Optional.of(stale));
        when(repository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(assembler.assemble(any())).thenReturn(mock(EngineInput.class));
        org.mockito.Mockito.doThrow(new AmqpException("broker down"))
                .when(publisher)
                .publish(any());

        republisher.republishUnpublished();

        ArgumentCaptor<BacktestRun> captor = ArgumentCaptor.forClass(BacktestRun.class);
        verify(repository).update(captor.capture());
        assertThat(captor.getValue().getLifecycle().publishedAt()).isNull();
        assertThat(captor.getValue().getLifecycle().publishCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("보낼 것이 없으면 아무것도 하지 않는다")
    void doesNothingWhenNothingIsStale() {
        when(repository.findUnpublishedQueued(any(), anyInt())).thenReturn(List.of());

        republisher.republishUnpublished();

        verify(publisher, never()).publish(any());
        verify(repository, never()).update(any());
    }
}
