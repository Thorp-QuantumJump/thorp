package com.ssafy.thorp.backtest.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.backtest.domain.vo.BacktestId;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BacktestRunTest {

    private static final BacktestPeriod PERIOD =
            new BacktestPeriod(LocalDate.of(2019, 1, 1), LocalDate.of(2023, 12, 31));
    private static final InstrumentId SAMSUNG = new InstrumentId("005930.XKRX");
    private static final InstrumentId SK = new InstrumentId("000660.XKRX");
    private static final BigDecimal CASH = new BigDecimal("10000000");

    private static BacktestRun create(
            List<InstrumentId> instrumentIds, BigDecimal initialCash, BigDecimal maxPositionPct) {
        return BacktestRun.create(
                "MA_CROSSOVER",
                "1.0.0",
                Map.of("shortWindow", 20),
                instrumentIds,
                PERIOD,
                initialCash,
                PositionSizingMode.STRATEGY_DEFINED,
                maxPositionPct,
                null,
                42);
    }

    @Test
    @DisplayName("create는 id·backtestId 없이 QUEUED 상태로 만든다")
    void createStartsQueuedWithoutId() {
        BacktestRun job = create(List.of(SAMSUNG), CASH, new BigDecimal("0.50"));

        assertThat(job.getId()).isNull();
        assertThat(job.getBacktestId()).isNull();
        assertThat(job.getStrategyId()).isEqualTo("MA_CROSSOVER");
        assertThat(job.getStrategyVersion()).isEqualTo("1.0.0");
        assertThat(job.getParameters()).containsEntry("shortWindow", 20);
        assertThat(job.getInstrumentIds()).containsExactly(SAMSUNG);
        assertThat(job.getPeriod()).isEqualTo(PERIOD);
        assertThat(job.getInitialCash()).isEqualTo(CASH);
        assertThat(job.getPositionSizingType()).isEqualTo(PositionSizingMode.STRATEGY_DEFINED);
        assertThat(job.getMaxPositionPct()).isEqualTo(new BigDecimal("0.50"));
        assertThat(job.getBenchmarkInstrumentId()).isNull();
        assertThat(job.getRandomSeed()).isEqualTo(42);
        assertThat(job.getStatus()).isEqualTo(RunStatus.QUEUED);
    }

    @Test
    @DisplayName("create는 빈 전략 식별자를 거부한다")
    void createRejectsBlankStrategyId() {
        assertThatThrownBy(
                        () ->
                                BacktestRun.create(
                                        " ",
                                        "1.0.0",
                                        Map.of(),
                                        List.of(SAMSUNG),
                                        PERIOD,
                                        CASH,
                                        PositionSizingMode.STRATEGY_DEFINED,
                                        null,
                                        null,
                                        42))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("strategyId는 저장 컬럼 폭까지만 허용한다")
    void createRejectsTooLongStrategyId() {
        String maxLength = "A".repeat(BacktestRun.MAX_STRATEGY_ID_LENGTH);
        assertThat(
                        BacktestRun.create(
                                        maxLength,
                                        "1.0.0",
                                        Map.of(),
                                        List.of(SAMSUNG),
                                        PERIOD,
                                        CASH,
                                        PositionSizingMode.STRATEGY_DEFINED,
                                        null,
                                        null,
                                        42)
                                .getStrategyId())
                .isEqualTo(maxLength);

        assertThatThrownBy(
                        () ->
                                BacktestRun.create(
                                        maxLength + "A",
                                        "1.0.0",
                                        Map.of(),
                                        List.of(SAMSUNG),
                                        PERIOD,
                                        CASH,
                                        PositionSizingMode.STRATEGY_DEFINED,
                                        null,
                                        null,
                                        42))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("parameters에 null 값이 있으면 도메인 오류로 거부한다")
    void createRejectsNullParameterValue() {
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("shortWindow", null);

        assertThatThrownBy(
                        () ->
                                BacktestRun.create(
                                        "MA_CROSSOVER",
                                        "1.0.0",
                                        withNull,
                                        List.of(SAMSUNG),
                                        PERIOD,
                                        CASH,
                                        PositionSizingMode.STRATEGY_DEFINED,
                                        null,
                                        null,
                                        42))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("create는 빈 종목 목록과 중복 종목을 거부한다")
    void createRejectsEmptyOrDuplicateInstruments() {
        assertThatThrownBy(() -> create(List.of(), CASH, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create(List.of(SAMSUNG, SK, SAMSUNG), CASH, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("create는 0 이하의 초기 자본을 거부한다")
    void createRejectsNonPositiveCash() {
        assertThatThrownBy(() -> create(List.of(SAMSUNG), BigDecimal.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create(List.of(SAMSUNG), new BigDecimal("-1"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("maxPositionPct는 (0, 1] 범위만 허용한다")
    void createRejectsMaxPositionPctOutOfRange() {
        assertThatThrownBy(() -> create(List.of(SAMSUNG), CASH, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create(List.of(SAMSUNG), CASH, new BigDecimal("1.01")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(create(List.of(SAMSUNG), CASH, BigDecimal.ONE).getMaxPositionPct())
                .isEqualTo(BigDecimal.ONE);
    }

    @Test
    @DisplayName("parameters와 instrumentIds는 방어적으로 복사해 불변으로 담는다")
    void createCopiesCollections() {
        BacktestRun job = create(List.of(SAMSUNG), CASH, null);

        assertThatThrownBy(() -> job.getInstrumentIds().add(SK))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> job.getParameters().put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static BacktestRun restoreWith(RunStatus status) {
        return BacktestRun.restore(
                7L,
                BacktestId.from("BT-20260907-000007"),
                "MA_CROSSOVER",
                "1.0.0",
                Map.of("shortWindow", 20),
                List.of(SAMSUNG),
                PERIOD,
                CASH,
                PositionSizingMode.STRATEGY_DEFINED,
                new BigDecimal("0.50"),
                SK,
                42,
                status);
    }

    @Test
    @DisplayName("markRunning은 QUEUED에서 RUNNING으로 바꾸고 나머지 필드는 그대로 둔다")
    void markRunningFromQueued() {
        BacktestRun queued = restoreWith(RunStatus.QUEUED);

        BacktestRun running = queued.markRunning("EXEC-000001");

        assertThat(running.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(queued.getStatus()).as("원본은 불변").isEqualTo(RunStatus.QUEUED);
        assertThat(running.getId()).isEqualTo(7L);
        assertThat(running.getBacktestId()).isEqualTo(BacktestId.from("BT-20260907-000007"));
        assertThat(running.getStrategyId()).isEqualTo("MA_CROSSOVER");
        assertThat(running.getStrategyVersion()).isEqualTo("1.0.0");
        assertThat(running.getParameters()).containsEntry("shortWindow", 20);
        assertThat(running.getInstrumentIds()).containsExactly(SAMSUNG);
        assertThat(running.getPeriod()).isEqualTo(PERIOD);
        assertThat(running.getInitialCash()).isEqualTo(CASH);
        assertThat(running.getPositionSizingType()).isEqualTo(PositionSizingMode.STRATEGY_DEFINED);
        assertThat(running.getMaxPositionPct()).isEqualTo(new BigDecimal("0.50"));
        assertThat(running.getBenchmarkInstrumentId()).isEqualTo(SK);
        assertThat(running.getRandomSeed()).isEqualTo(42);
    }

    @ParameterizedTest
    @EnumSource(
            value = RunStatus.class,
            names = {"COMPLETED", "FAILED", "CANCELLED"})
    @DisplayName("markRunning은 최종 상태에서 거부한다")
    void markRunningRejectsFinal(RunStatus status) {
        assertThatThrownBy(() -> restoreWith(status).markRunning("EXEC-000001"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markRunning은 시도 횟수를 올리고 실행 시도 식별자를 남긴다")
    void markRunningRecordsAttempt() {
        BacktestRun running = restoreWith(RunStatus.QUEUED).markRunning("EXEC-000001");

        assertThat(running.getAttempt()).isEqualTo(1);
        assertThat(running.getCurrentExecutionId()).isEqualTo("EXEC-000001");
        assertThat(running.getProgress().stage()).isEqualTo(ProgressStage.PREPARING_DATA);
    }

    @Test
    @DisplayName("RUNNING 중 다른 executionId의 STARTED는 시도만 올린다 — 브로커 재전달(자동 재시도)이다")
    void secondStartedKeepsRunningAndBumpsAttempt() {
        BacktestRun first = restoreWith(RunStatus.QUEUED).markRunning("EXEC-000001");

        BacktestRun retried = first.markRunning("EXEC-000002");

        assertThat(retried.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(retried.getAttempt()).isEqualTo(2);
        assertThat(retried.getCurrentExecutionId()).isEqualTo("EXEC-000002");
    }

    @Test
    @DisplayName("같은 executionId의 중복 STARTED는 아무것도 바꾸지 않는다")
    void duplicateStartedIsIgnored() {
        BacktestRun first = restoreWith(RunStatus.QUEUED).markRunning("EXEC-000001");

        assertThat(first.markRunning("EXEC-000001")).isSameAs(first);
    }

    @Test
    @DisplayName("markCancelled는 부분 결과 표시와 정지한 거래일을 남긴다")
    void markCancelledKeepsStoppedDate() {
        BacktestRun running = restoreWith(RunStatus.QUEUED).markRunning("EXEC-000001");

        BacktestRun cancelled = running.markCancelled(LocalDate.of(2025, 3, 25));

        assertThat(cancelled.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(cancelled.isPartial()).isTrue();
        assertThat(cancelled.getProgress().lastTradingDate()).isEqualTo(LocalDate.of(2025, 3, 25));
    }

    @Test
    @DisplayName("markFailed는 사유 코드를 남기고, 코드가 비면 거부한다")
    void markFailedKeepsFailureCode() {
        BacktestRun failed = restoreWith(RunStatus.QUEUED).markFailed("DATA_INSUFFICIENT", "자료 없음");

        assertThat(failed.getFailureCode()).isEqualTo("DATA_INSUFFICIENT");
        assertThat(failed.getFailureMessage()).isEqualTo("자료 없음");
        assertThatThrownBy(() -> restoreWith(RunStatus.QUEUED).markFailed(" ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("recordProgress는 RUNNING에서만 허용한다")
    void recordProgressOnlyWhileRunning() {
        BacktestRun running = restoreWith(RunStatus.QUEUED).markRunning("EXEC-000001");
        RunProgress progress =
                new RunProgress(ProgressStage.COMPUTING, LocalDate.of(2025, 1, 28), 20, 129);

        assertThat(running.recordProgress(progress).getProgress()).isEqualTo(progress);
        assertThatThrownBy(() -> restoreWith(RunStatus.QUEUED).recordProgress(progress))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @EnumSource(
            value = RunStatus.class,
            names = {"QUEUED", "RUNNING"})
    @DisplayName("markCompleted·markFailed는 비최종 상태(QUEUED 포함)에서 허용한다")
    void finalTransitionsAllowedFromNonFinal(RunStatus status) {
        assertThat(restoreWith(status).markCompleted().getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(restoreWith(status).markFailed("ENGINE_ERROR", "boom").getStatus())
                .isEqualTo(RunStatus.FAILED);
    }

    @ParameterizedTest
    @EnumSource(
            value = RunStatus.class,
            names = {"COMPLETED", "FAILED", "CANCELLED"})
    @DisplayName("최종 상태에서는 어떤 전이도 거부한다")
    void finalStateRejectsAllTransitions(RunStatus status) {
        BacktestRun run = restoreWith(status);

        assertThatThrownBy(() -> run.markRunning("EXEC-000001"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(run::markCompleted).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> run.markFailed("ENGINE_ERROR", "boom"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> run.markCancelled(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("restore는 저장된 id·backtestId와 상태를 그대로 복원한다")
    void restoreKeepsIdAndStatus() {
        BacktestId backtestId = BacktestId.from("BT-20260907-000007");
        BacktestRun job =
                BacktestRun.restore(
                        7L,
                        backtestId,
                        "MA_CROSSOVER",
                        "1.0.0",
                        Map.of(),
                        List.of(SAMSUNG),
                        PERIOD,
                        CASH,
                        PositionSizingMode.STRATEGY_DEFINED,
                        null,
                        SK,
                        42,
                        RunStatus.RUNNING);

        assertThat(job.getId()).isEqualTo(7L);
        assertThat(job.getBacktestId()).isEqualTo(backtestId);
        assertThat(job.getBenchmarkInstrumentId()).isEqualTo(SK);
        assertThat(job.getStatus()).isEqualTo(RunStatus.RUNNING);
    }
}
