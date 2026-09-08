package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.backtest.application.port.message.EngineInput;
import com.ssafy.thorp.backtest.application.port.message.PositionSizingType;
import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import com.ssafy.thorp.backtest.domain.model.BacktestStatus;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.vo.BacktestId;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 필드 매핑과 고정 정책 값만 본다. 직렬화 동치는 EngineInputFixtureTest가 픽스처로 확인한다. */
class EngineInputAssemblerTest {

    private static final BacktestPeriod PERIOD =
            new BacktestPeriod(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 6, 30));

    private final EngineInputAssembler assembler = new EngineInputAssembler();

    private static BacktestJob job(BacktestId backtestId, InstrumentId benchmark) {
        return BacktestJob.restore(
                1L,
                backtestId,
                "MA_CROSSOVER",
                "1.0.0",
                Map.of("shortWindow", 20, "threshold", 0.05),
                List.of(new InstrumentId("005930.XKRX"), new InstrumentId("000660.XKRX")),
                PERIOD,
                new BigDecimal("10000000"),
                PositionSizingMode.STRATEGY_DEFINED,
                new BigDecimal("0.50"),
                benchmark,
                42,
                BacktestStatus.QUEUED);
    }

    @Test
    @DisplayName("Job의 사용자 입력을 그대로 옮긴다")
    void mapsJobFields() {
        EngineInput input =
                assembler.assemble(
                        job(
                                BacktestId.from("BT-20260907-000001"),
                                new InstrumentId("069500.XKRX")));

        assertThat(input.run().backtestId()).isEqualTo("BT-20260907-000001");
        assertThat(input.run().start()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(input.run().end()).isEqualTo(LocalDate.of(2025, 6, 30));
        assertThat(input.run().randomSeed()).isEqualTo(42);
        assertThat(input.universe().instrumentIds()).containsExactly("005930.XKRX", "000660.XKRX");
        assertThat(input.strategy().strategyId()).isEqualTo("MA_CROSSOVER");
        assertThat(input.strategy().version()).isEqualTo("1.0.0");
        assertThat(input.strategy().parameters()).containsEntry("shortWindow", 20);
        assertThat(input.portfolio().initialCash()).isEqualByComparingTo("10000000");
        assertThat(input.portfolio().positionSizing().type())
                .isEqualTo(PositionSizingType.STRATEGY_DEFINED);
        assertThat(input.portfolio().positionSizing().maxPositionPct())
                .isEqualByComparingTo("0.50");
        assertThat(input.benchmark().instrumentId()).isEqualTo("069500.XKRX");
    }

    @Test
    @DisplayName("고정 정책은 ExecutionPolicy에서 온다")
    void fillsFixedPolicy() {
        EngineInput input = assembler.assemble(job(BacktestId.from("BT-20260907-000001"), null));

        assertThat(input.marketData()).isEqualTo(ExecutionPolicy.MARKET_DATA);
        assertThat(input.execution()).isEqualTo(ExecutionPolicy.EXECUTION);
        assertThat(input.portfolio().currency()).isEqualTo(ExecutionPolicy.CURRENCY);
        assertThat(input.portfolio().shortSelling()).isEqualTo(ExecutionPolicy.SHORT_SELLING);
        assertThat(input.portfolio().leverage()).isEqualByComparingTo(ExecutionPolicy.LEVERAGE);
    }

    @Test
    @DisplayName("벤치마크가 없으면 null로 넘긴다")
    void passesNullBenchmark() {
        EngineInput input = assembler.assemble(job(BacktestId.from("BT-20260907-000001"), null));

        assertThat(input.benchmark()).isNull();
    }

    @Test
    @DisplayName("backtestId가 없는 Job은 조립할 수 없다")
    void rejectsJobWithoutBacktestId() {
        assertThatThrownBy(() -> assembler.assemble(job(null, null)))
                .isInstanceOf(IllegalStateException.class);
    }
}
