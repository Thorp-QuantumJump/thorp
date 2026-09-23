package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 동일 버전 키(spec 004 research R-03).
 *
 * <p>이 키가 같다는 것은 "다시 계산해도 같은 숫자가 나온다"는 선언이고, 그 위에서 남의 결과를 그대로 보여 준다. 그래서 두 방향 모두 틀리면 안 된다 — <b>같아야
 * 할 것이 달라지면</b> 같은 계산을 두 번 하고, <b>달라야 할 것이 같아지면</b> 조건이 다른 결과를 보여 준다. 뒤쪽이 훨씬 나쁘다.
 */
class DedupKeyCalculatorTest {

    private final DedupKeyCalculator calculator = new DedupKeyCalculator();

    private static DedupKeyCalculator.Input base() {
        return new DedupKeyCalculator.Input(
                "FIXED_WEIGHT",
                "1",
                Map.of("rebalance", "MONTH_END"),
                "SECTOR-ETF-2026-09",
                List.of(
                        new DedupKeyCalculator.UniverseEntry(
                                "SEMICONDUCTOR", "091160.XKRX", new BigDecimal("0.4000")),
                        new DedupKeyCalculator.UniverseEntry(
                                "BATTERY", "305720.XKRX", new BigDecimal("0.6000"))),
                LocalDate.of(2023, 7, 3),
                LocalDate.of(2024, 12, 31),
                63,
                new BigDecimal("10000000.00"),
                "FIXTURE-three_etf_24m",
                "SPLIT-1",
                "PROVISIONAL-DEMO-1",
                "nautilus_trader 1.219.0 / worker 2.3.0 / uv.lock 8e8a6be0a6a8",
                "FULL_PERIOD",
                null,
                null);
    }

    private String keyOf(UnaryOperator<DedupKeyCalculator.Input> change) {
        return calculator.calculate(change.apply(base()));
    }

    @Test
    @DisplayName("키는 SHA-256 hex 64자이고 같은 입력에 항상 같다")
    void keyIsStableSha256Hex() {
        String first = calculator.calculate(base());

        assertThat(first).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(calculator.calculate(base())).isEqualTo(first);
    }

    @Nested
    @DisplayName("결과를 바꾸지 않는 차이는 같은 키다")
    class SameKey {

        @Test
        @DisplayName("종목 순서만 다르면 같은 실행이다 — 화면이 정렬을 바꿔도 다시 계산하지 않는다")
        void universeOrderDoesNotMatter() {
            String reversed =
                    keyOf(
                            input ->
                                    new DedupKeyCalculator.Input(
                                            input.strategyId(),
                                            input.rulesVersion(),
                                            input.parameters(),
                                            input.mappingVersion(),
                                            input.universe().reversed(),
                                            input.start(),
                                            input.end(),
                                            input.warmupTradingDays(),
                                            input.initialCash(),
                                            input.dataVersion(),
                                            input.evaluationSplitVersion(),
                                            input.costProfileVersion(),
                                            input.engineVersion(),
                                            input.scenario(),
                                            input.scenarioParams(),
                                            input.scenarioRulesVersion()));

            assertThat(reversed).isEqualTo(calculator.calculate(base()));
        }

        @Test
        @DisplayName("소수의 자릿수 표기만 다르면 같은 비중이다")
        void decimalScaleDoesNotMatter() {
            String shortScale =
                    keyOf(
                            input ->
                                    withWeights(
                                            input, new BigDecimal("0.4"), new BigDecimal("0.6")));

            assertThat(shortScale).isEqualTo(calculator.calculate(base()));
        }

        @Test
        @DisplayName("파라미터 키 순서는 영향이 없다")
        void parameterOrderDoesNotMatter() {
            Map<String, Object> ordered = new LinkedHashMap<>();
            ordered.put("zzz", 1);
            ordered.put("aaa", 2);
            Map<String, Object> reversed = new LinkedHashMap<>();
            reversed.put("aaa", 2);
            reversed.put("zzz", 1);

            assertThat(keyOf(input -> withParameters(input, ordered)))
                    .isEqualTo(keyOf(input -> withParameters(input, reversed)));
        }
    }

    @Nested
    @DisplayName("결과를 바꾸는 차이는 다른 키다")
    class DifferentKey {

        @Test
        @DisplayName("워밍업이 다르면 평가 시작일이 달라진다 — 비교 묶음이 다르면 같은 전략도 다른 실행이다")
        void warmupIsPartOfTheKey() {
            assertThat(keyOf(input -> withWarmup(input, 120)))
                    .isNotEqualTo(calculator.calculate(base()));
        }

        @Test
        @DisplayName("자료 버전이 다르면 다른 실행이다 — 픽스처 결과를 실제 자료 결과로 재사용하지 않는다")
        void dataVersionIsPartOfTheKey() {
            assertThat(keyOf(input -> withDataVersion(input, "KRX_DAILY_20260911")))
                    .isNotEqualTo(calculator.calculate(base()));
        }

        @Test
        @DisplayName("워커 실행환경 지문(uv.lock)만 달라도 다른 실행이다 — 의존성이 바뀐 환경의 결과를 재사용하지 않는다")
        void workerEnvironmentFingerprintIsPartOfTheKey() {
            assertThat(
                            keyOf(
                                    input ->
                                            withEngineVersion(
                                                    input,
                                                    "nautilus_trader 1.219.0 / worker 2.3.0"
                                                            + " / uv.lock 000000000000")))
                    .isNotEqualTo(calculator.calculate(base()));
        }

        @Test
        @DisplayName("비중이 다르면 다른 실행이다")
        void weightsArePartOfTheKey() {
            assertThat(
                            keyOf(
                                    input ->
                                            withWeights(
                                                    input,
                                                    new BigDecimal("0.5000"),
                                                    new BigDecimal("0.5000"))))
                    .isNotEqualTo(calculator.calculate(base()));
        }

        @Test
        @DisplayName("시나리오와 그 입력값이 다르면 다른 실행이다")
        void scenarioIsPartOfTheKey() {
            String targetRisk =
                    keyOf(
                            input ->
                                    withScenario(
                                            input,
                                            "TARGET_RISK",
                                            Map.of("targetVolatility", new BigDecimal("0.1000")),
                                            "1"));
            String otherTarget =
                    keyOf(
                            input ->
                                    withScenario(
                                            input,
                                            "TARGET_RISK",
                                            Map.of("targetVolatility", new BigDecimal("0.1500")),
                                            "1"));

            assertThat(targetRisk).isNotEqualTo(calculator.calculate(base()));
            assertThat(targetRisk).isNotEqualTo(otherTarget);
        }
    }

    @Test
    @DisplayName("canonical JSON 은 키가 사전순이고 소수·날짜가 문자열이다")
    void canonicalJsonIsSortedAndStringly() {
        String json = calculator.canonicalJson(base());

        assertThat(json).startsWith("{\"costProfileVersion\":");
        assertThat(json).contains("\"initialCash\":\"10000000\"");
        assertThat(json).contains("\"start\":\"2023-07-03\"");
        assertThat(json).as("null 항목은 키 자체가 없다").doesNotContain("scenarioParams");
    }

    // --- 입력 변형 도우미 ---------------------------------------------------

    private static DedupKeyCalculator.Input withWeights(
            DedupKeyCalculator.Input input, BigDecimal first, BigDecimal second) {
        List<DedupKeyCalculator.UniverseEntry> universe =
                List.of(
                        new DedupKeyCalculator.UniverseEntry("SEMICONDUCTOR", "091160.XKRX", first),
                        new DedupKeyCalculator.UniverseEntry("BATTERY", "305720.XKRX", second));
        return new DedupKeyCalculator.Input(
                input.strategyId(),
                input.rulesVersion(),
                input.parameters(),
                input.mappingVersion(),
                universe,
                input.start(),
                input.end(),
                input.warmupTradingDays(),
                input.initialCash(),
                input.dataVersion(),
                input.evaluationSplitVersion(),
                input.costProfileVersion(),
                input.engineVersion(),
                input.scenario(),
                input.scenarioParams(),
                input.scenarioRulesVersion());
    }

    private static DedupKeyCalculator.Input withParameters(
            DedupKeyCalculator.Input input, Map<String, Object> parameters) {
        return new DedupKeyCalculator.Input(
                input.strategyId(),
                input.rulesVersion(),
                parameters,
                input.mappingVersion(),
                input.universe(),
                input.start(),
                input.end(),
                input.warmupTradingDays(),
                input.initialCash(),
                input.dataVersion(),
                input.evaluationSplitVersion(),
                input.costProfileVersion(),
                input.engineVersion(),
                input.scenario(),
                input.scenarioParams(),
                input.scenarioRulesVersion());
    }

    private static DedupKeyCalculator.Input withWarmup(DedupKeyCalculator.Input input, int warmup) {
        return new DedupKeyCalculator.Input(
                input.strategyId(),
                input.rulesVersion(),
                input.parameters(),
                input.mappingVersion(),
                input.universe(),
                input.start(),
                input.end(),
                warmup,
                input.initialCash(),
                input.dataVersion(),
                input.evaluationSplitVersion(),
                input.costProfileVersion(),
                input.engineVersion(),
                input.scenario(),
                input.scenarioParams(),
                input.scenarioRulesVersion());
    }

    private static DedupKeyCalculator.Input withDataVersion(
            DedupKeyCalculator.Input input, String dataVersion) {
        return new DedupKeyCalculator.Input(
                input.strategyId(),
                input.rulesVersion(),
                input.parameters(),
                input.mappingVersion(),
                input.universe(),
                input.start(),
                input.end(),
                input.warmupTradingDays(),
                input.initialCash(),
                dataVersion,
                input.evaluationSplitVersion(),
                input.costProfileVersion(),
                input.engineVersion(),
                input.scenario(),
                input.scenarioParams(),
                input.scenarioRulesVersion());
    }

    private static DedupKeyCalculator.Input withEngineVersion(
            DedupKeyCalculator.Input input, String engineVersion) {
        return new DedupKeyCalculator.Input(
                input.strategyId(),
                input.rulesVersion(),
                input.parameters(),
                input.mappingVersion(),
                input.universe(),
                input.start(),
                input.end(),
                input.warmupTradingDays(),
                input.initialCash(),
                input.dataVersion(),
                input.evaluationSplitVersion(),
                input.costProfileVersion(),
                engineVersion,
                input.scenario(),
                input.scenarioParams(),
                input.scenarioRulesVersion());
    }

    private static DedupKeyCalculator.Input withScenario(
            DedupKeyCalculator.Input input,
            String scenario,
            Map<String, Object> params,
            String rulesVersion) {
        return new DedupKeyCalculator.Input(
                input.strategyId(),
                input.rulesVersion(),
                input.parameters(),
                input.mappingVersion(),
                input.universe(),
                input.start(),
                input.end(),
                input.warmupTradingDays(),
                input.initialCash(),
                input.dataVersion(),
                input.evaluationSplitVersion(),
                input.costProfileVersion(),
                input.engineVersion(),
                scenario,
                params,
                rulesVersion);
    }
}
