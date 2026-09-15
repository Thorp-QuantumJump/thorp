package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * 커밋 뒤 발행이 접수를 실패로 만들지 않는지(spec 004 research R-18).
 *
 * <p>{@code AFTER_COMMIT} 콜백에서 나간 예외는 삼켜지지 않고 커밋을 호출한 쪽으로 전파된다. 접수는 이미 커밋됐고 작업은 브로커에 들어갔는데 사용자만
 * 500을 받는 상황이 되고, 그러면 사용자가 다시 눌러 같은 요청이 하나 더 생긴다. 발행 실패든 그 뒤 기록 실패든 이 경계를 넘으면 안 된다.
 */
class BacktestJobDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-09-11T07:00:00Z");

    private final BacktestRunRepository repository = mock(BacktestRunRepository.class);
    private final BacktestJobPublisher publisher = mock(BacktestJobPublisher.class);
    private final EngineInputAssembler assembler = mock(EngineInputAssembler.class);

    /** 트랜잭션 경계는 여기 관심사가 아니다 — 콜백을 그대로 실행한다. 전파 설정은 IT가 실제 DB로 확인한다. */
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
            new BacktestJobDispatcher(
                    repository,
                    publisher,
                    assembler,
                    transactionManager,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private static BacktestRun run() {
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
                RunStatus.QUEUED);
    }

    /** 발행 시도 횟수를 지정한 실행. 재발행기가 올려 둔 상태를 흉내 낸다. */
    private static BacktestRun runWithPublishCount(int publishCount) {
        RunLifecycle lifecycle = RunLifecycle.queuedAt(NOW);
        for (int i = 0; i < publishCount; i++) {
            lifecycle = lifecycle.publishAttempted();
        }
        return run().withLifecycle(lifecycle);
    }

    @Test
    @DisplayName("최초 발행은 접수가 확보한 시도 번호를 쓴다 — 늦어진 사이 재발행기가 올려 둔 값을 따라가지 않는다")
    void theFirstPublishKeepsItsOwnAttemptNumber() {
        // 접수는 1을 확보했는데 콜백이 늦어졌고, 그 사이 재발행기가 2로 올려 시도 2를 이미 보냈다.
        // 여기서 DB 의 최신값(2)을 읽으면 서로 다른 두 메시지가 같은 번호를 달고, DLQ 집계가 둘을
        // 한 건으로 접어 dlq_count < publish_count 로 남는다 — 종결 판정이 영영 서지 않는다.
        when(repository.findById(11L)).thenReturn(Optional.of(runWithPublishCount(2)));
        when(assembler.assemble(any())).thenReturn(mock(EngineInput.class));

        dispatcher.onRunAccepted(new BacktestJobDispatcher.RunAccepted(11L, 1));

        verify(publisher).publish(any(), eq(1), any());
    }

    @Test
    @DisplayName("재발행은 자기가 올린 번호를 쓴다")
    void republishUsesTheNumberItClaimed() {
        when(repository.findById(11L)).thenReturn(Optional.of(runWithPublishCount(2)));
        when(assembler.assemble(any())).thenReturn(mock(EngineInput.class));

        dispatcher.republish(11L);

        verify(repository).recordPublishAttempt(11L);
        verify(publisher).publish(any(), eq(2), any());
    }

    @Test
    @DisplayName("발행에 실패해도 접수 쪽으로 예외가 나가지 않는다")
    void publishFailureDoesNotEscape() {
        when(repository.findById(11L)).thenReturn(Optional.of(run()));
        when(assembler.assemble(any())).thenReturn(mock(EngineInput.class));
        doThrow(new AmqpException("broker down")).when(publisher).publish(any(), anyInt(), any());

        assertThatCode(
                        () ->
                                dispatcher.onRunAccepted(
                                        new BacktestJobDispatcher.RunAccepted(11L, 1)))
                .doesNotThrowAnyException();
        verify(repository, never()).recordPublished(any(), any());
    }

    @Test
    @DisplayName("발행 뒤 기록에 실패해도 예외가 나가지 않는다 — 재발행기가 집어 간다")
    void recordFailureDoesNotEscape() {
        when(repository.findById(11L)).thenReturn(Optional.of(run()));
        when(assembler.assemble(any())).thenReturn(mock(EngineInput.class));
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(repository)
                .recordPublished(any(), any());

        assertThatCode(
                        () ->
                                dispatcher.onRunAccepted(
                                        new BacktestJobDispatcher.RunAccepted(11L, 1)))
                .doesNotThrowAnyException();
        verify(publisher).publish(any(), anyInt(), any());
    }

    @Test
    @DisplayName("실행 조회에 실패해도 예외가 나가지 않는다")
    void lookupFailureDoesNotEscape() {
        when(repository.findById(11L)).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatCode(
                        () ->
                                dispatcher.onRunAccepted(
                                        new BacktestJobDispatcher.RunAccepted(11L, 1)))
                .doesNotThrowAnyException();
        verify(publisher, never()).publish(any(), anyInt(), any());
    }

    @Test
    @DisplayName("모르는 실행이면 발행하지 않고 조용히 끝낸다")
    void unknownRunIsSkipped() {
        when(repository.findById(11L)).thenReturn(Optional.empty());

        assertThatCode(
                        () ->
                                dispatcher.onRunAccepted(
                                        new BacktestJobDispatcher.RunAccepted(11L, 1)))
                .doesNotThrowAnyException();
        verify(publisher, never()).publish(any(), anyInt(), any());
    }
}
