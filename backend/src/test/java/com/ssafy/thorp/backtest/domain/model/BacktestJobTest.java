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

class BacktestJobTest {

    private static final BacktestPeriod PERIOD =
            new BacktestPeriod(LocalDate.of(2019, 1, 1), LocalDate.of(2023, 12, 31));
    private static final InstrumentId SAMSUNG = new InstrumentId("005930.XKRX");
    private static final InstrumentId SK = new InstrumentId("000660.XKRX");
    private static final BigDecimal CASH = new BigDecimal("10000000");

    private static BacktestJob create(
            List<InstrumentId> instrumentIds, BigDecimal initialCash, BigDecimal maxPositionPct) {
        return BacktestJob.create(
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
        BacktestJob job = create(List.of(SAMSUNG), CASH, new BigDecimal("0.50"));

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
        assertThat(job.getStatus()).isEqualTo(BacktestStatus.QUEUED);
    }

    @Test
    @DisplayName("create는 빈 전략 식별자를 거부한다")
    void createRejectsBlankStrategyId() {
        assertThatThrownBy(
                        () ->
                                BacktestJob.create(
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
        String maxLength = "A".repeat(BacktestJob.MAX_STRATEGY_ID_LENGTH);
        assertThat(
                        BacktestJob.create(
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
                                BacktestJob.create(
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
                                BacktestJob.create(
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
        BacktestJob job = create(List.of(SAMSUNG), CASH, null);

        assertThatThrownBy(() -> job.getInstrumentIds().add(SK))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> job.getParameters().put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("restore는 저장된 id·backtestId와 상태를 그대로 복원한다")
    void restoreKeepsIdAndStatus() {
        BacktestId backtestId = BacktestId.from("BT-20260907-000007");
        BacktestJob job =
                BacktestJob.restore(
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
                        BacktestStatus.RUNNING);

        assertThat(job.getId()).isEqualTo(7L);
        assertThat(job.getBacktestId()).isEqualTo(backtestId);
        assertThat(job.getBenchmarkInstrumentId()).isEqualTo(SK);
        assertThat(job.getStatus()).isEqualTo(BacktestStatus.RUNNING);
    }
}
