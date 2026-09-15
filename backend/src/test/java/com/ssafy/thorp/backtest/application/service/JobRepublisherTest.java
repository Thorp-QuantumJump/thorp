package com.ssafy.thorp.backtest.application.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
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
import org.mockito.InOrder;
import org.springframework.amqp.AmqpException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

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

    /**
     * 트랜잭션 경계는 이 테스트의 관심사가 아니다 — 콜백을 그대로 실행하는 가짜 매니저를 준다.
     *
     * <p>전파 설정이 실제로 맞는지(AFTER_COMMIT 에서 새 트랜잭션을 여는지)는 여기서 확인할 수 없다. 그것은 {@code
     * BacktestPublishAfterCommitIT} 가 실제 DB 로 확인한다.
     */
    private final PlatformTransactionManager transactionManager =
            new AbstractPlatformTransactionManager() {
                @Override
                protected Object doGetTransaction() {
                    return new Object();
                }

                @Override
                protected void doBegin(Object transaction, TransactionDefinition definition) {}

                @Override
                protected void doCommit(DefaultTransactionStatus status) {}

                @Override
                protected void doRollback(DefaultTransactionStatus status) {}
            };

    private final BacktestJobDispatcher dispatcher =
            new BacktestJobDispatcher(repository, publisher, assembler, transactionManager, clock);

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
                new RunLifecycle(
                        NOW.minusSeconds(600),
                        null,
                        null,
                        publishedAt,
                        publishCount,
                        0,
                        null,
                        null,
                        null));
    }

    private void givenStaleRun() {
        BacktestRun stale = queuedRun(1, null);
        when(repository.findUnpublishedQueued(any(), anyInt())).thenReturn(List.of(stale));
        when(repository.findById(11L)).thenReturn(Optional.of(stale));
        when(assembler.assemble(any())).thenReturn(mock(EngineInput.class));
    }

    @Test
    @DisplayName("보내기 전에 시도 횟수를 먼저 올린다")
    void countsBeforeSending() {
        givenStaleRun();

        republisher.republishUnpublished();

        InOrder order = inOrder(repository, publisher);
        order.verify(repository).recordPublishAttempt(11L);
        order.verify(publisher).publish(any(), anyInt());
    }

    @Test
    @DisplayName("재발행이 성공하면 발행 확인 시각을 남긴다 — 다음 주기에 다시 집어 가지 않는다")
    void marksPublishedOnSuccess() {
        givenStaleRun();

        republisher.republishUnpublished();

        verify(repository).recordPublished(11L, NOW);
    }

    @Test
    @DisplayName("재발행이 또 실패하면 발행 확인을 남기지 않는다 — 다음 주기가 다시 집어 간다")
    void keepsUnpublishedOnFailure() {
        givenStaleRun();
        org.mockito.Mockito.doThrow(new AmqpException("broker down"))
                .when(publisher)
                .publish(any(), anyInt());

        republisher.republishUnpublished();

        verify(repository).recordPublishAttempt(11L);
        verify(repository, never()).recordPublished(any(), any());
    }

    @Test
    @DisplayName("발행 경로는 실행 상태를 저장하지 않는다 — 이벤트가 바꾼 상태를 되돌리면 안 된다")
    void neverWritesRunState() {
        givenStaleRun();

        republisher.republishUnpublished();

        verify(repository, never()).update(any());
    }

    @Test
    @DisplayName("보낼 것이 없으면 아무것도 하지 않는다")
    void doesNothingWhenNothingIsStale() {
        when(repository.findUnpublishedQueued(any(), anyInt())).thenReturn(List.of());

        republisher.republishUnpublished();

        verify(publisher, never()).publish(any(), anyInt());
        verify(repository, never()).recordPublishAttempt(any());
    }
}
