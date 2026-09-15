package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.backtest.application.event.BacktestFinished;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * 커밋 뒤 발행이 접수를 실패로 만들지 않는지(spec 004 research R-18).
 *
 * <p>그리고 <b>어떤 실패를 사용자 실패로 닫을지</b>. 조립이 영구히 실패한 실행은 메시지가 나간 적이 없어 DLQ 종결 판정이 서지 않으므로 닫아야 하지만, DB 나
 * 브로커가 잠깐 흔들린 것까지 닫으면 멀쩡한 요청이 실패로 끝난다. 닫는 것은 <b>영구라고 아는 예외 + 첫 발행</b>일 때뿐이고, 아래 검사들이 그 경계를 지킨다.
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

    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private final BacktestJobDispatcher dispatcher =
            new BacktestJobDispatcher(
                    repository,
                    publisher,
                    assembler,
                    events,
                    transactionManager,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private static BacktestRun run() {
        return runWithStatus(RunStatus.QUEUED);
    }

    private static BacktestRun runWithStatus(RunStatus status) {
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
                status);
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

        verify(publisher).publish(any(), eq(1));
    }

    @Test
    @DisplayName("재발행은 자기가 올린 번호를 쓴다")
    void republishUsesTheNumberItClaimed() {
        when(repository.findById(11L)).thenReturn(Optional.of(runWithPublishCount(2)));
        when(assembler.assemble(any())).thenReturn(mock(EngineInput.class));

        dispatcher.republish(11L);

        verify(repository).recordPublishAttempt(11L);
        verify(publisher).publish(any(), eq(2));
    }

    @Test
    @DisplayName("발행에 실패해도 접수 쪽으로 예외가 나가지 않는다")
    void publishFailureDoesNotEscape() {
        when(repository.findById(11L)).thenReturn(Optional.of(run()));
        when(assembler.assemble(any())).thenReturn(mock(EngineInput.class));
        doThrow(new AmqpException("broker down")).when(publisher).publish(any(), anyInt());

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
        verify(publisher).publish(any(), anyInt());
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
        verify(publisher, never()).publish(any(), anyInt());
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
        verify(publisher, never()).publish(any(), anyInt());
    }

    @Test
    @DisplayName("첫 발행에서 조립이 영구히 실패하면 FAILED 로 닫고 종료 이벤트를 낸다 — 재발행에 맡기면 QUEUED 에 갇힌다")
    void assemblyFailureClosesTheRun() {
        // 조립 실패는 메시지가 브로커에 나간 적이 없으므로 dlq_count 가 오르지 않는다. 재발행기에
        // 맡기면 종결 판정(dlqCount >= publishCount)이 영영 서지 않고 시도 횟수만 무한히 오른다.
        when(repository.findById(11L)).thenReturn(Optional.of(run()));
        when(repository.findByIdForUpdate(11L)).thenReturn(Optional.of(run()));
        when(assembler.assemble(any())).thenThrow(rulesNotRegistered());

        assertThatCode(
                        () ->
                                dispatcher.onRunAccepted(
                                        new BacktestJobDispatcher.RunAccepted(11L, 1)))
                .doesNotThrowAnyException();

        verify(publisher, never()).publish(any(), anyInt());
        // published_at 은 NULL 로 남는다. 상태가 QUEUED 가 아니게 되므로 재발행 대상에서 빠진다.
        verify(repository, never()).recordPublished(any(), any());

        ArgumentCaptor<BacktestRun> saved = ArgumentCaptor.forClass(BacktestRun.class);
        verify(repository).update(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(saved.getValue().getLifecycle().finishedAt()).isEqualTo(NOW);

        ArgumentCaptor<BacktestFinished> finished = ArgumentCaptor.forClass(BacktestFinished.class);
        verify(events).publishEvent(finished.capture());
        assertThat(finished.getValue().backtestJobId()).isEqualTo(11L);
        assertThat(finished.getValue().backtestId()).isEqualTo("BT-20260911-000011");
        assertThat(finished.getValue().status()).isEqualTo(RunStatus.FAILED);
        assertThat(finished.getValue().errorCode()).isEqualTo("SYSTEM_FAILURE");
    }

    @Test
    @DisplayName("발행이 실패하면 상태는 QUEUED 그대로다 — 브로커 일시 장애를 사용자 실패로 만들지 않는다")
    void publishFailureLeavesTheRunQueued() {
        // 이 검사가 이번 수정의 안전장치다. 조립 실패와 같은 길로 닫아 버리면 브로커가 잠깐 흔들린
        // 것만으로 멀쩡한 요청이 실패로 끝난다.
        when(repository.findById(11L)).thenReturn(Optional.of(run()));
        when(assembler.assemble(any())).thenReturn(mock(EngineInput.class));
        doThrow(new AmqpException("broker down")).when(publisher).publish(any(), anyInt());

        dispatcher.onRunAccepted(new BacktestJobDispatcher.RunAccepted(11L, 1));

        verify(repository, never()).findByIdForUpdate(any());
        verify(repository, never()).update(any());
        verify(events, never()).publishEvent(any(BacktestFinished.class));
        verify(repository, never()).recordPublished(any(), any());
    }

    @Test
    @DisplayName("이미 RUNNING 인 실행은 조립이 실패해도 상태를 바꾸지 않는다")
    void runningRunIsNotOverwritten() {
        // 읽고 판단하고 쓰는 사이에 상태가 바뀔 수 있다. 잠그고 다시 읽어 확인한다.
        when(repository.findById(11L)).thenReturn(Optional.of(run()));
        when(repository.findByIdForUpdate(11L))
                .thenReturn(Optional.of(runWithStatus(RunStatus.RUNNING)));
        when(assembler.assemble(any())).thenThrow(rulesNotRegistered());

        dispatcher.onRunAccepted(new BacktestJobDispatcher.RunAccepted(11L, 1));

        verify(repository).findByIdForUpdate(11L);
        verify(repository, never()).update(any());
        verify(events, never()).publishEvent(any(BacktestFinished.class));
    }

    @Test
    @DisplayName("조립이 DB 자원 접근 실패로 터지면 닫지 않는다 — DB 가 잠깐 흔들린 것이 사용자 실패가 되면 안 된다")
    void transientAssemblyFailureLeavesTheRunQueued() {
        // EngineInputAssembler 는 전략 규칙·비용 등록표를 DB 에서 읽는다. 커넥션 풀 고갈이나 DB
        // 재기동이면 여기서 DataAccessException 이 나오는데, 30초 뒤 재발행하면 그냥 성공한다.
        // 몇 줄 위 findById 가 같은 예외를 일시로 다루는 것과도 어긋나면 안 된다.
        when(repository.findById(11L)).thenReturn(Optional.of(run()));
        when(assembler.assemble(any()))
                .thenThrow(new DataAccessResourceFailureException("pool exhausted"));

        dispatcher.onRunAccepted(new BacktestJobDispatcher.RunAccepted(11L, 1));

        verify(repository, never()).findByIdForUpdate(any());
        verify(repository, never()).update(any());
        verify(events, never()).publishEvent(any(BacktestFinished.class));
        verify(repository, never()).recordPublished(any(), any());
    }

    @Test
    @DisplayName("재발행에서는 조립이 영구히 실패해도 닫지 않는다 — 같은 실패를 첫 발행은 닫는다")
    void republishDoesNotCloseOnAssemblyFailure() {
        // 재발행 대상이 되었다는 것은 발행에 성공하고 published_at 기록만 실패했을 수 있다는 뜻이다.
        // 그때 워커는 이미 계산 중인데 STARTED 가 아직 처리되기 전이라 상태는 QUEUED 다. 여기서
        // 닫으면 뒤늦게 올 COMPLETED 가 최종 상태 규칙에 걸려 버려진다.
        when(repository.findById(11L)).thenReturn(Optional.of(run()));
        when(repository.findByIdForUpdate(11L)).thenReturn(Optional.of(run()));
        when(assembler.assemble(any())).thenThrow(rulesNotRegistered());

        dispatcher.republish(11L);

        verify(repository, never()).findByIdForUpdate(any());
        verify(repository, never()).update(any());
        verify(events, never()).publishEvent(any(BacktestFinished.class));

        // 같은 실패가 첫 발행 경로에서는 닫힌다. 두 경로를 나란히 둬야 갈림이 의도임이 드러난다.
        dispatcher.onRunAccepted(new BacktestJobDispatcher.RunAccepted(11L, 1));

        verify(repository).findByIdForUpdate(11L);
        verify(repository).update(any());
        verify(events).publishEvent(any(BacktestFinished.class));
    }

    /** 조립이 스스로 판단해 거부한 실패들. 저장된 실행의 값은 바뀌지 않으므로 다시 읽어도 같은 결과다. */
    static Stream<RuntimeException> permanentAssemblyFailures() {
        return Stream.of(
                // 등록표에 규칙이 없다(422). StrategyRulesService.requireRegistered 가 던진다.
                rulesNotRegistered(),
                // 비용 버전이 등록표에 없다(400). CostProfileService.resolve 가 던진다.
                new BusinessException(ErrorCode.INVALID_INPUT, "unknown cost profile version: X"),
                // 서버 기본 비용 행이 없다(500). 설정과 마이그레이션이 어긋난 것이라 저절로 낫지 않는다.
                new BusinessException(ErrorCode.INTERNAL_ERROR, "default cost profile missing"),
                // 저장된 요청 사본의 scenario 문자열이 계약에 없다. Scenario.valueOf 가 던진다.
                new IllegalArgumentException("No enum constant Scenario.UNKNOWN"),
                // targetWeight 가 숫자가 아니다. new BigDecimal(...) 이 던진다.
                new NumberFormatException("Character array is missing \"exponent\""),
                // 요청 사본의 필드 타입이 계약과 다르다. (String) spec.get(...) 이 던진다.
                new ClassCastException("Integer cannot be cast to String"));
    }

    @ParameterizedTest
    @MethodSource("permanentAssemblyFailures")
    @DisplayName("영구 실패로 아는 예외는 첫 발행에서 실행을 닫는다")
    void permanentAssemblyFailuresAreClosed(RuntimeException failure) {
        when(repository.findById(11L)).thenReturn(Optional.of(run()));
        when(repository.findByIdForUpdate(11L)).thenReturn(Optional.of(run()));
        when(assembler.assemble(any())).thenThrow(failure);

        dispatcher.onRunAccepted(new BacktestJobDispatcher.RunAccepted(11L, 1));

        ArgumentCaptor<BacktestRun> saved = ArgumentCaptor.forClass(BacktestRun.class);
        verify(repository).update(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(RunStatus.FAILED);
        verify(events).publishEvent(any(BacktestFinished.class));
    }

    private static BusinessException rulesNotRegistered() {
        return new BusinessException(
                ErrorCode.RULES_NOT_REGISTERED, "strategy rules not available: FIXED_WEIGHT/1");
    }
}
