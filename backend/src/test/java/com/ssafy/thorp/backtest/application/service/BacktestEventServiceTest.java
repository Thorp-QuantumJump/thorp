package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.backtest.application.command.SaveBacktestResultCommand;
import com.ssafy.thorp.backtest.application.event.BacktestFinished;
import com.ssafy.thorp.backtest.application.port.message.BacktestEvent;
import com.ssafy.thorp.backtest.application.port.message.FinishedPayload;
import com.ssafy.thorp.backtest.application.port.message.ProgressPayload;
import com.ssafy.thorp.backtest.application.repository.BacktestResultRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.model.ProgressStage;
import com.ssafy.thorp.backtest.domain.model.RunState;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.backtest.domain.vo.BacktestId;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.backtest.infrastructure.messaging.MessageFixtures;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/** 상태에 따른 적용·무시 판단을 고정한다. 픽스처의 backtestId는 {@code BT-20260907-000001}이다. */
@ExtendWith(MockitoExtension.class)
class BacktestEventServiceTest {

    private static final String BACKTEST_ID = "BT-20260907-000001";

    @Mock private BacktestRunRepository jobRepository;
    @Mock private BacktestResultRepository resultRepository;
    @Mock private ApplicationEventPublisher events;

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-11T05:00:00Z"), ZoneOffset.UTC);
    private BacktestEventService service;

    @BeforeEach
    void createService() {
        service = new BacktestEventService(jobRepository, resultRepository, events, clock);
    }

    private static BacktestRun jobIn(RunStatus status) {
        return BacktestRun.restore(
                1L,
                BacktestId.from(BACKTEST_ID),
                "MA_CROSSOVER",
                "1.0.0",
                Map.of(),
                List.of(new InstrumentId("005930.XKRX")),
                new BacktestPeriod(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 6, 30)),
                new BigDecimal("10000000.00"),
                PositionSizingMode.STRATEGY_DEFINED,
                null,
                null,
                42,
                status);
    }

    private void givenJob(RunStatus status) {
        when(jobRepository.findByBacktestId(BACKTEST_ID)).thenReturn(Optional.of(jobIn(status)));
    }

    /** 마지막 STARTED 의 executionId 를 지정한 실행. 픽스처 이벤트의 executionId 와 일부러 다르게 둔다. */
    private void givenJob(RunStatus status, String currentExecutionId) {
        BacktestRun job =
                BacktestRun.restore(
                        1L,
                        BacktestId.from(BACKTEST_ID),
                        "MA_CROSSOVER",
                        "1.0.0",
                        Map.of(),
                        List.of(new InstrumentId("005930.XKRX")),
                        new BacktestPeriod(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 6, 30)),
                        new BigDecimal("10000000.00"),
                        PositionSizingMode.STRATEGY_DEFINED,
                        null,
                        null,
                        42,
                        new RunState(status, 1, currentExecutionId, false, null, null, null));
        when(jobRepository.findByBacktestId(BACKTEST_ID)).thenReturn(Optional.of(job));
    }

    private void givenUnknownJob() {
        when(jobRepository.findByBacktestId(BACKTEST_ID)).thenReturn(Optional.empty());
    }

    private BacktestRun updatedJob() {
        ArgumentCaptor<BacktestRun> captor = ArgumentCaptor.forClass(BacktestRun.class);
        verify(jobRepository).update(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("STARTED: QUEUED Job을 RUNNING으로 갱신한다")
    void startedMovesQueuedToRunning() {
        givenJob(RunStatus.QUEUED);

        service.handleStarted(MessageFixtures.started());

        assertThat(updatedJob().getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @ParameterizedTest
    @EnumSource(
            value = RunStatus.class,
            names = {"COMPLETED", "FAILED", "CANCELLED"})
    @DisplayName("STARTED: 이미 끝난 실행이면 갱신하지 않는다(늦은 이벤트)")
    void lateStartedIsIgnored(RunStatus status) {
        givenJob(status);

        service.handleStarted(MessageFixtures.started());

        verify(jobRepository, never()).update(any());
    }

    @Test
    @DisplayName("STARTED: RUNNING 중 다른 실행이 시작하면 상태는 그대로 두고 시도만 올린다 (R-06 자동 재시도)")
    void startedFromAnotherExecutionBumpsAttempt() {
        givenJob(RunStatus.RUNNING, "exec-previous");

        service.handleStarted(MessageFixtures.started());

        BacktestRun updated = updatedJob();
        assertThat(updated.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(updated.getAttempt()).isEqualTo(2);
    }

    @Test
    @DisplayName("FINISHED(COMPLETED): 결과를 저장하고 Job을 COMPLETED로 갱신한다")
    void finishedCompletedSavesResultAndCompletes() {
        givenJob(RunStatus.RUNNING);
        BacktestEvent<FinishedPayload> event = MessageFixtures.finishedCompleted();

        service.handleFinished(event);

        ArgumentCaptor<SaveBacktestResultCommand> captor =
                ArgumentCaptor.forClass(SaveBacktestResultCommand.class);
        verify(resultRepository).save(captor.capture());
        SaveBacktestResultCommand command = captor.getValue();
        assertThat(command.runId()).isEqualTo(1L);
        assertThat(command.executionId()).isEqualTo(event.executionId());
        assertThat(command.eventId()).isEqualTo(event.eventId());
        assertThat(command.engineOutput()).isSameAs(event.payload().engineOutput());
        assertThat(command.benchmark()).isNull(); // v2 FINISHED에는 벤치마크가 없다
        assertThat(updatedJob().getStatus()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    @DisplayName("FINISHED: 끝난 실행을 BacktestFinished로 알린다 — 007이 큐를 다시 소비하지 않는 이유다")
    void finishedPublishesDomainEvent() {
        givenJob(RunStatus.RUNNING);
        BacktestEvent<FinishedPayload> event = MessageFixtures.finishedCompleted();

        service.handleFinished(event);

        ArgumentCaptor<BacktestFinished> captor = ArgumentCaptor.forClass(BacktestFinished.class);
        verify(events).publishEvent(captor.capture());
        BacktestFinished published = captor.getValue();
        assertThat(published.backtestJobId()).isEqualTo(1L);
        assertThat(published.backtestId()).isEqualTo(BACKTEST_ID);
        assertThat(published.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(published.errorCode()).isNull();
    }

    @Test
    @DisplayName("FINISHED(FAILED): 실패 사유 코드를 이벤트에 담는다")
    void finishedFailedPublishesErrorCode() {
        givenJob(RunStatus.RUNNING);

        service.handleFinished(MessageFixtures.finishedFailed());

        ArgumentCaptor<BacktestFinished> captor = ArgumentCaptor.forClass(BacktestFinished.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(RunStatus.FAILED);
        assertThat(captor.getValue().errorCode()).isEqualTo("DATA_INSUFFICIENT");
    }

    @Test
    @DisplayName("늦게 온 FINISHED는 아무것도 알리지 않는다 — 끝난 실행을 두 번 알리면 후보가 두 번 갱신된다")
    void lateFinishedPublishesNothing() {
        givenJob(RunStatus.COMPLETED);

        service.handleFinished(MessageFixtures.finishedCompleted());

        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("FINISHED(CANCELLED): 실행 모델(T003)이 생기기 전에는 저장하지 않고 예외를 낸다")
    void finishedCancelledIsNotSupportedYet() {
        givenJob(RunStatus.RUNNING);

        assertThatThrownBy(() -> service.handleFinished(MessageFixtures.finishedCancelled()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining(BACKTEST_ID);
        verifyNoInteractions(resultRepository);
        verify(jobRepository, never()).update(any());
    }

    @Test
    @DisplayName("FINISHED(FAILED): 결과(오류 포함)를 저장하고 Job을 FAILED로 갱신한다")
    void finishedFailedSavesResultAndFails() {
        givenJob(RunStatus.RUNNING);
        BacktestEvent<FinishedPayload> event = MessageFixtures.finishedFailed();

        service.handleFinished(event);

        ArgumentCaptor<SaveBacktestResultCommand> captor =
                ArgumentCaptor.forClass(SaveBacktestResultCommand.class);
        verify(resultRepository).save(captor.capture());
        assertThat(captor.getValue().engineOutput().run().error().code())
                .isEqualTo("DATA_INSUFFICIENT");
        assertThat(updatedJob().getStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    @DisplayName("FINISHED: STARTED가 유실돼 QUEUED인 Job도 저장하고 최종화한다")
    void finishedOnQueuedJobIsApplied() {
        givenJob(RunStatus.QUEUED);

        service.handleFinished(MessageFixtures.finishedCompleted());

        verify(resultRepository).save(any());
        assertThat(updatedJob().getStatus()).isEqualTo(RunStatus.COMPLETED);
    }

    @ParameterizedTest
    @EnumSource(
            value = RunStatus.class,
            names = {"COMPLETED", "FAILED"})
    @DisplayName("FINISHED: 이미 최종 상태면 저장도 갱신도 하지 않는다(멱등·첫 결과 채택)")
    void finishedOnFinalJobIsIgnored(RunStatus status) {
        givenJob(status);

        service.handleFinished(MessageFixtures.finishedCompleted());

        verifyNoInteractions(resultRepository);
        verify(jobRepository, never()).update(any());
    }

    @Test
    @DisplayName("모르는 backtestId의 이벤트는 아무것도 하지 않는다(버림)")
    void unknownBacktestIdIsDropped() {
        givenUnknownJob();

        service.handleStarted(MessageFixtures.started());
        service.handleFinished(MessageFixtures.finishedCompleted());
        service.handleProgress(MessageFixtures.progress());

        verify(jobRepository, never()).update(any());
        verifyNoInteractions(resultRepository);
    }

    @Test
    @DisplayName("PROGRESS: RUNNING 실행의 진행은 실행 행에 저장한다 (R-05)")
    void progressOnRunningJobIsPersisted() {
        givenJob(RunStatus.RUNNING);
        BacktestEvent<ProgressPayload> event = MessageFixtures.progress();

        service.handleProgress(event);

        ArgumentCaptor<BacktestRun> captor = ArgumentCaptor.forClass(BacktestRun.class);
        verify(jobRepository).update(captor.capture());
        assertThat(captor.getValue().getProgress().stage()).isEqualTo(ProgressStage.COMPUTING);
        assertThat(captor.getValue().getProgress().lastTradingDate())
                .isEqualTo(event.payload().lastTradingDate());
        assertThat(captor.getValue().getProgress().completedTradingDays())
                .isEqualTo(event.payload().completedTradingDays());
    }

    @ParameterizedTest
    @EnumSource(
            value = RunStatus.class,
            names = {"QUEUED", "COMPLETED", "FAILED"})
    @DisplayName("PROGRESS: RUNNING이 아닌 실행의 진행은 저장하지 않는다")
    void progressOnNonRunningJobIsIgnored(RunStatus status) {
        givenJob(status);

        service.handleProgress(MessageFixtures.progress());

        verify(jobRepository, never()).update(any());
    }

    // --- R-18 중복 실행 종결 규칙 -------------------------------------------

    @Test
    @DisplayName("다른 실행의 FAILED는 무시하고 RUNNING을 유지한다 — 뒤에 올 정상 결과를 덮지 않는다")
    void failedFromSupersededExecutionIsIgnored() {
        givenJob(RunStatus.RUNNING, "exec-current");

        service.handleFinished(MessageFixtures.finishedFailed());

        verify(jobRepository, never()).update(any());
        verifyNoInteractions(resultRepository);
    }

    @Test
    @DisplayName("COMPLETED는 어느 실행에서 와도 적용한다 — 입력이 같으므로 결과가 같다")
    void completedFromAnyExecutionApplies() {
        givenJob(RunStatus.RUNNING, "exec-current");

        service.handleFinished(MessageFixtures.finishedCompleted());

        ArgumentCaptor<BacktestRun> captor = ArgumentCaptor.forClass(BacktestRun.class);
        verify(jobRepository).update(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(captor.getValue().getLifecycle().finishedAt()).isEqualTo(clock.instant());
    }
}
