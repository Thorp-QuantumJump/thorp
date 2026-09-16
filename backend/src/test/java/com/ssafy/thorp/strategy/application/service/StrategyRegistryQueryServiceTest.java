package com.ssafy.thorp.strategy.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.ssafy.thorp.strategy.application.port.RegisteredChoice;
import com.ssafy.thorp.strategy.application.result.StrategyRegistryResult;
import com.ssafy.thorp.strategy.infrastructure.registry.SyntheticRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 등록표 조회의 판정 규칙을 본다(T027).
 *
 * <p>합성 등록표로 돌린다. 운영 등록표의 값에 기대면 값이 바뀔 때마다 테스트가 따라 바뀌고, 그러면 확인하는 것이 판정 규칙인지 그날의 수치인지 알 수 없다.
 *
 * <p>확인하는 것은 넷이다 — 활성 여부로 목록에서 빼는가, 고를 수 없는 것을 <b>표시한 채로</b> 싣는가, 판정의 범위가 축마다 다른가, 워밍업을 기본값으로 더하는가.
 */
class StrategyRegistryQueryServiceTest {

    private final StrategyRegistryQueryService service =
            new StrategyRegistryQueryService(SyntheticRegistry.contract());

    @Nested
    @DisplayName("섹터 수를 주면")
    class WithUniverseSize {

        @Test
        @DisplayName("고를 수 없는 후보가 표시된 채로 목록에 남는다 (quickstart 3)")
        void unselectableChoicesRemainInTheList() {
            List<StrategyRegistryResult.ChoiceView> choices =
                    choices(service.query(2), "SECTOR_MOMENTUM", "topN");

            // 섹터가 둘이므로 3·4는 고를 수 없다. 그래도 목록에서 빠지지 않는다 —
            // 사라지면 사용자가 왜 고를 수 없는지 알 방법이 없다.
            assertThat(choices)
                    .extracting(StrategyRegistryResult.ChoiceView::value)
                    .containsExactly(2, 3, 4);
            assertThat(choices)
                    .extracting(StrategyRegistryResult.ChoiceView::selectable)
                    .containsExactly(true, false, false);
        }

        @Test
        @DisplayName("기본 설정이 규칙을 어기는 전략은 고를 수 없고 이유가 규칙의 detail 그대로다")
        void violatingStrategyCarriesTheRuleDetail() {
            StrategyRegistryResult.StrategyView momentum =
                    strategy(service.query(3), "SECTOR_MOMENTUM");

            // 기본 선정 수가 4인데 섹터가 셋이다.
            assertThat(momentum.selectable()).isFalse();
            assertThat(momentum.unselectableReason()).isEqualTo("선정 섹터 수가 후보 섹터 집합의 크기보다 큽니다.");
        }

        @Test
        @DisplayName("규칙을 어기지 않는 전략은 이유 없이 고를 수 있다")
        void satisfyingStrategyHasNoReason() {
            StrategyRegistryResult.StrategyView inverse =
                    strategy(service.query(3), "INVERSE_VOLATILITY");

            // 비중 상한 40 × 섹터 3 = 120이라 전액을 배분할 수 있다.
            assertThat(inverse.selectable()).isTrue();
            assertThat(inverse.unselectableReason()).isNull();
        }

        @Test
        @DisplayName("그 축을 참조하지 않는 규칙 때문에 다른 축의 후보가 잠기지 않는다")
        void unrelatedAxesStaySelectable() {
            StrategyRegistryResult result = service.query(2);

            // 선정 수가 규칙을 어겨 전략 자체는 고를 수 없는 상태다.
            assertThat(strategy(result, "SECTOR_MOMENTUM").selectable()).isFalse();

            // 그렇다고 관측 기간 후보까지 잠그면 "20거래일을 고를 수 없다"는 틀린 안내가 된다.
            assertThat(choices(result, "SECTOR_MOMENTUM", "lookbackDays"))
                    .allMatch(StrategyRegistryResult.ChoiceView::selectable);
            assertThat(choices(result, "SECTOR_MOMENTUM", "skipRecentDays"))
                    .allMatch(StrategyRegistryResult.ChoiceView::selectable);
        }
    }

    @Nested
    @DisplayName("섹터 수를 주지 않으면")
    class WithoutUniverseSize {

        @Test
        @DisplayName("제약을 적용하지 않는다 — 없는 근거로 막지 않는다")
        void nothingIsConstrained() {
            StrategyRegistryResult result = service.query(null);

            assertThat(result.strategies())
                    .allMatch(StrategyRegistryResult.StrategyView::selectable);
            assertThat(result.strategies())
                    .allSatisfy(strategy -> assertThat(strategy.unselectableReason()).isNull());
            assertThat(choices(result, "SECTOR_MOMENTUM", "topN"))
                    .allMatch(StrategyRegistryResult.ChoiceView::selectable);
        }

        @Test
        @DisplayName("규칙 목록은 그대로 실린다 — 규칙 목록이지 위반 판정이 아니다 (quickstart 3)")
        void rulesAreAlwaysIncluded() {
            List<StrategyRegistryResult.RuleView> withoutSize =
                    strategy(service.query(null), "SECTOR_MOMENTUM").validationRules();
            List<StrategyRegistryResult.RuleView> withSize =
                    strategy(service.query(3), "SECTOR_MOMENTUM").validationRules();

            assertThat(withoutSize).isEqualTo(withSize);
            assertThat(withoutSize)
                    .singleElement()
                    .satisfies(
                            rule -> {
                                assertThat(rule.code()).isEqualTo("TOP_N_EXCEEDS_UNIVERSE");
                                assertThat(rule.left().axis()).isEqualTo("topN");
                                assertThat(rule.operator())
                                        .isEqualTo(
                                                com.ssafy.thorp.strategy.application.port
                                                        .ValidationRule.Operator.GREATER_THAN);
                                assertThat(rule.right().context()).isEqualTo("UNIVERSE_SIZE");
                            });
        }
    }

    @Nested
    @DisplayName("워밍업은")
    class Warmup {

        @Test
        @DisplayName("축의 기본값을 모두 더한 값이다")
        void isTheSumOfDefaults() {
            StrategyRegistryResult result = service.query(3);

            // 역변동성은 축 하나(63), 섹터 모멘텀은 둘을 더한다(60 + 21).
            assertThat(strategy(result, "INVERSE_VOLATILITY").warmupTradingDays()).isEqualTo(63);
            assertThat(strategy(result, "SECTOR_MOMENTUM").warmupTradingDays()).isEqualTo(81);
        }

        @Test
        @DisplayName("규칙이 없는 전략은 0이고 warmupRule을 싣지 않는다")
        void isZeroWithoutARule() {
            StrategyRegistryResult result =
                    new StrategyRegistryQueryService(SyntheticRegistry.of(TWO_STRATEGIES))
                            .query(null);

            StrategyRegistryResult.StrategyView noWarmup = strategy(result, "ACTIVE_ONE");
            assertThat(noWarmup.warmupTradingDays()).isZero();
            assertThat(noWarmup.warmupRule()).isNull();
        }
    }

    @Test
    @DisplayName("비활성 전략은 목록에 아예 없다 — selectable: false로 싣는 것과 다르다 (quickstart 3-1)")
    void inactiveStrategiesAreAbsent() {
        StrategyRegistryResult result =
                new StrategyRegistryQueryService(SyntheticRegistry.of(TWO_STRATEGIES)).query(3);

        assertThat(result.strategies())
                .extracting(StrategyRegistryResult.StrategyView::strategyId)
                .containsExactly("ACTIVE_ONE");
    }

    @Nested
    @DisplayName("후보의 표시 문구는")
    class ChoiceLabels {

        @Test
        @DisplayName("등록표가 적었으면 그대로 싣는다 — 화면이 값의 뜻을 짐작하지 않는다")
        void comeFromTheRegistry() {
            List<StrategyRegistryResult.ChoiceView> modes =
                    choices(service.query(3), "SECTOR_MOMENTUM", "selectionMode");

            assertThat(modes)
                    .extracting(StrategyRegistryResult.ChoiceView::label)
                    .containsExactly("상위 몇 개", "기준 수익률 이상");
        }

        @Test
        @DisplayName("적지 않은 숫자 후보는 값과 축의 단위로 만든다")
        void fallBackToValueAndUnit() {
            List<StrategyRegistryResult.ChoiceView> lookback =
                    choices(service.query(3), "INVERSE_VOLATILITY", "lookbackDays");

            assertThat(lookback)
                    .extracting(StrategyRegistryResult.ChoiceView::label)
                    .containsExactly("21거래일", "63거래일", "126거래일", "252거래일");
        }

        @Test
        @DisplayName("단위가 없는 숫자 후보는 값 그대로다")
        void fallBackToValueAlone() {
            assertThat(choices(service.query(3), "SECTOR_MOMENTUM", "topN"))
                    .extracting(StrategyRegistryResult.ChoiceView::label)
                    .containsExactly("2", "3", "4");
        }

        @Test
        @DisplayName("분석 조건의 후보에도 실린다 — 0이 '0년'이 아니라 '제한 없음'이라는 것은 등록표만 안다")
        void areCarriedOnAnalysisConditions() {
            StrategyRegistryResult.ConditionView minEvaluationYears =
                    service.query(3).analysisConditions().minEvaluationYears();

            assertThat(minEvaluationYears.choices())
                    .extracting(RegisteredChoice::value, RegisteredChoice::label)
                    .containsExactly(tuple(0, "완화(제한 없음)"), tuple(1, "기본(1년)"), tuple(3, "엄격(3년)"));
        }
    }

    @Test
    @DisplayName("등록표 버전과 임시 여부를 그대로 싣는다")
    void versionIsCarriedThrough() {
        StrategyRegistryResult result = service.query(null);

        assertThat(result.registryVersion()).isEqualTo("PROVISIONAL-20260911");
        assertThat(result.provisional()).isTrue();
    }

    private static StrategyRegistryResult.StrategyView strategy(
            StrategyRegistryResult result, String strategyId) {
        return result.strategies().stream()
                .filter(strategy -> strategy.strategyId().equals(strategyId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no such strategy: " + strategyId));
    }

    private static List<StrategyRegistryResult.ChoiceView> choices(
            StrategyRegistryResult result, String strategyId, String axisName) {
        return strategy(result, strategyId).axes().stream()
                .filter(axis -> axis.name().equals(axisName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no such axis: " + axisName))
                .choices();
    }

    /** 활성 하나와 비활성 하나. 워밍업 규칙을 적지 않아 0인 경우도 함께 본다. */
    private static final String TWO_STRATEGIES =
            """
            version: "1.0.0"
            analysisConditions:
              initialAmount:
                min: 1000000
                max: 1000000000
                step: 1000000
                default: 10000000
                unit: 원
              targetVolatility:
                min: 5
                max: 20
                step: 1
                default: 10
                unit: 퍼센트(연)
                usedBy: TARGET_RISK
              minEvaluationYears:
                values: [0, 1, 3]
                default: 1
                unit: 년
            strategies:
              - id: ACTIVE_ONE
                name: 도는 전략
                targetWeightSource: EQUAL
                axes:
                  - name: rebalancePeriod
                    label: 리밸런싱 주기
                    searchValues:
                      - { mode: CALENDAR, unit: MONTH_END }
                    defaultValue: { mode: CALENDAR, unit: MONTH_END }
                    allowedRange:
                      choices:
                        - value: { mode: CALENDAR, unit: MONTH_END }
                          label: 월말
                        - value: { mode: CALENDAR, unit: QUARTER_END }
                          label: 분기말
                        - value: { mode: TRADING_DAYS, days: 21 }
                          label: 21거래일
              - id: RETIRED_ONE
                name: 내린 전략
                active: false
                targetWeightSource: COMPUTED
                axes:
                  - name: lookbackDays
                    label: 관측 기간
                    unit: 거래일
                    searchValues: [60]
                    defaultValue: 60
                    allowedRange:
                      values: [60]
            """;
}
