package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.backtest.application.port.message.EngineInput;
import com.ssafy.thorp.backtest.application.port.message.PositionSizingType;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.CostProfile;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.model.RulesStatus;
import com.ssafy.thorp.backtest.domain.model.RunPlan;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.backtest.domain.model.StrategyRules;
import com.ssafy.thorp.backtest.domain.vo.BacktestId;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 저장된 실행 → 작업 메시지(schemaVersion 2).
 *
 * <p>핵심은 "조립 시점에 새로 고르는 값이 없다"는 것이다. 규칙 사본·비용 값·자료 버전은 전부 접수가 확정한 계획이 가리키는 등록표에서 온다. 재발행이 며칠 뒤에
 * 일어나도 같은 메시지가 나와야 재현이 성립한다.
 */
class EngineInputAssemblerTest {

    private static final BacktestPeriod PERIOD =
            new BacktestPeriod(LocalDate.of(2023, 7, 3), LocalDate.of(2024, 12, 31));

    private final StrategyRulesService strategyRulesService = mock(StrategyRulesService.class);
    private final CostProfileService costProfileService = mock(CostProfileService.class);
    private final EngineInputAssembler assembler =
            new EngineInputAssembler(strategyRulesService, costProfileService);

    private static BacktestRun run(RunPlan plan) {
        return BacktestRun.restore(
                        1L,
                        BacktestId.from("BT-20260907-000001"),
                        "INVERSE_VOLATILITY",
                        "1",
                        Map.of("rebalance", "MONTH_END"),
                        List.of(new InstrumentId("091160.XKRX"), new InstrumentId("305720.XKRX")),
                        PERIOD,
                        new BigDecimal("10000000"),
                        PositionSizingMode.STRATEGY_DEFINED,
                        null,
                        null,
                        42,
                        RunStatus.QUEUED)
                .withPlan(plan);
    }

    private static RunPlan acceptedPlan(String scenario, Map<String, Object> scenarioParams) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("mappingVersion", "SECTOR-ETF-2026-09");
        spec.put(
                "items",
                List.of(
                        Map.of(
                                "sectorId",
                                "SEMICONDUCTOR",
                                "instrumentId",
                                "091160.XKRX",
                                "targetWeight",
                                "0.4000"),
                        Map.of(
                                "sectorId",
                                "BATTERY",
                                "instrumentId",
                                "305720.XKRX",
                                "targetWeight",
                                "0.6000")));
        spec.put("evaluationSplitVersion", "SPLIT-1");
        spec.put("scenario", scenario);
        if (scenarioParams != null) {
            spec.put("scenarioParams", scenarioParams);
        }
        return new RunPlan(
                "a".repeat(64),
                "1",
                "PROVISIONAL-DEMO-1",
                "FIXTURE-three_etf_24m",
                LocalDate.of(2023, 10, 4),
                LocalDate.of(2024, 12, 31),
                63,
                spec,
                Map.of("rules", "1"));
    }

    private void registryAnswers() {
        when(strategyRulesService.requireRegistered("INVERSE_VOLATILITY", "1"))
                .thenReturn(
                        new StrategyRules(
                                "INVERSE_VOLATILITY",
                                "1",
                                RulesStatus.REGISTERED,
                                true,
                                Map.of("lookbackDays", 63),
                                "PD-02"));
        when(strategyRulesService.requireRegistered("TARGET_RISK_OVERLAY", "1"))
                .thenReturn(
                        new StrategyRules(
                                "TARGET_RISK_OVERLAY",
                                "1",
                                RulesStatus.REGISTERED,
                                false,
                                Map.of("lookbackDays", 63, "scaleDownOnly", true),
                                "PD-18"));
        when(costProfileService.resolve(any()))
                .thenReturn(
                        new CostProfile(
                                "PROVISIONAL-DEMO-1",
                                new BigDecimal("0"),
                                new BigDecimal("0"),
                                new BigDecimal("5"),
                                new BigDecimal("0"),
                                null,
                                true,
                                "ADR-0004"));
    }

    @Test
    @DisplayName("접수가 확정한 계획을 그대로 싣는다 — 섹터·비중·규칙 사본·비용·자료 버전·실효 시작일")
    void carriesTheAcceptedPlan() {
        registryAnswers();
        EngineInput input = assembler.assemble(run(acceptedPlan("FULL_PERIOD", null)));

        assertThat(input.run().runId()).isEqualTo("BT-20260907-000001");
        assertThat(input.run().effectiveStart()).isEqualTo(LocalDate.of(2023, 10, 4));
        assertThat(input.universe().mappingVersion()).isEqualTo("SECTOR-ETF-2026-09");
        assertThat(input.universe().items())
                .extracting(EngineInput.UniverseItem::sectorId)
                .containsExactly("SEMICONDUCTOR", "BATTERY");
        assertThat(input.universe().items().get(0).targetWeight()).isEqualByComparingTo("0.4000");
        assertThat(input.allocation().rulesVersion()).isEqualTo("1");
        assertThat(input.allocation().rules()).containsEntry("lookbackDays", 63);
        assertThat(input.marketData().dataVersion()).isEqualTo("FIXTURE-three_etf_24m");
        assertThat(input.execution().costProfile().version()).isEqualTo("PROVISIONAL-DEMO-1");
        assertThat(input.execution().costProfile().provisional()).isTrue();
        assertThat(input.evaluation().warmupTradingDays()).isEqualTo(63);
        assertThat(input.evaluation().splitVersion()).isEqualTo("SPLIT-1");
        assertThat(input.progress().batchTradingDays()).isEqualTo(20);
        assertThat(input.portfolio().positionSizing().type())
                .isEqualTo(PositionSizingType.STRATEGY_DEFINED);
    }

    @Test
    @DisplayName("목표 위험 시나리오는 오버레이 규칙 사본이 붙는다 — 사용자 입력이 아니라 등록표 값이다")
    void targetRiskCarriesOverlayRules() {
        registryAnswers();
        EngineInput input =
                assembler.assemble(
                        run(acceptedPlan("TARGET_RISK", Map.of("targetVolatility", "0.1000"))));

        assertThat(input.run().scenario()).isEqualTo(Scenario.TARGET_RISK);
        assertThat(input.run().scenarioParams()).containsEntry("targetVolatility", "0.1000");
        assertThat(input.run().scenarioRules()).containsEntry("scaleDownOnly", true);
    }

    @Test
    @DisplayName("주 분석에는 오버레이 규칙이 붙지 않는다")
    void fullPeriodHasNoOverlay() {
        registryAnswers();
        EngineInput input = assembler.assemble(run(acceptedPlan("FULL_PERIOD", null)));

        assertThat(input.run().scenarioRules()).isNull();
        assertThat(input.run().scenarioParams()).isNull();
    }

    @Test
    @DisplayName("계획이 없는 실행은 등록표를 읽지 않고 종목만으로 조립한다 (접수 경로를 거치지 않은 행)")
    void planlessRunFallsBackToInstrumentIds() {
        EngineInput input = assembler.assemble(run(RunPlan.empty()));

        assertThat(input.universe().mappingVersion()).isNull();
        assertThat(input.universe().items())
                .extracting(EngineInput.UniverseItem::instrumentId)
                .containsExactly("091160.XKRX", "305720.XKRX");
        assertThat(input.allocation().rules()).isNull();
        assertThat(input.execution().costProfile().version()).isEqualTo("PROVISIONAL-DEMO-1");
    }

    @Test
    @DisplayName("저장 전 실행은 발행할 수 없다 — runId 가 없으면 이벤트를 되짚을 수 없다")
    void unsavedRunIsRejected() {
        BacktestRun unsaved =
                BacktestRun.create(
                        "FIXED_WEIGHT",
                        "1",
                        Map.of(),
                        List.of(new InstrumentId("091160.XKRX")),
                        PERIOD,
                        new BigDecimal("10000000"),
                        PositionSizingMode.STRATEGY_DEFINED,
                        null,
                        null,
                        42);

        assertThatThrownBy(() -> assembler.assemble(unsaved))
                .isInstanceOf(IllegalStateException.class);
    }
}
