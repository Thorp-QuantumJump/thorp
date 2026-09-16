package com.ssafy.thorp.strategy.infrastructure.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.strategy.application.port.AnalysisCondition;
import com.ssafy.thorp.strategy.application.port.ParameterAxis;
import com.ssafy.thorp.strategy.application.port.SearchContext;
import com.ssafy.thorp.strategy.application.port.StrategyRegistration;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.yaml.snakeyaml.Yaml;

/**
 * 등록표 로더 검증(T018).
 *
 * <p><b>기대값을 이 테스트에 적지 않는다.</b> 전략·축·후보값을 테스트에 옮겨 적으면 그 순간 전략 데이터가 두 곳에 생기고, 자원 파일을 고쳐도 테스트가
 * 통과하는(또는 이유 없이 깨지는) 상태가 된다. 그래서 자원 파일을 따로 한 번 더 읽어 로더의 결과와 대조한다 — 확인하는 것은 값 자체가 아니라 <b>파일과 포트 결과가
 * 같다는 것</b>이다(007 FR-025).
 *
 * <p>규칙 평가와 기동 검사는 합성 등록표로 본다. 실제 파일의 값에 의존하면 값이 바뀔 때마다 테스트가 따라 바뀐다.
 */
class FileParameterRegistryTest {

    /** 합성 등록표마다 되풀이해 적지 않으려고 뽑아 둔 최소 분석 조건. 로더가 이 절을 필수로 본다. */
    private static final String CONDITIONS =
            """
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
            """;

    private final FileParameterRegistry registry = newRegistry();

    @Nested
    @DisplayName("자원 파일 적재")
    class Loading {

        @Test
        @DisplayName("전략·축·후보값·기본값·허용 범위가 자원 파일과 같다")
        void matchesResourceFile() {
            List<Map<String, Object>> fileStrategies = rawStrategies();

            assertThat(registry.activeStrategies())
                    .extracting(StrategyRegistration::strategyId)
                    .containsExactlyElementsOf(
                            fileStrategies.stream()
                                    .filter(FileParameterRegistryTest::isActive)
                                    .map(s -> String.valueOf(s.get("id")))
                                    .toList());

            for (Map<String, Object> fileStrategy : fileStrategies) {
                StrategyRegistration registration =
                        registry.find(String.valueOf(fileStrategy.get("id"))).orElseThrow();
                assertThat(registration.name()).isEqualTo(fileStrategy.get("name"));
                assertThat(registration.targetWeightSource())
                        .isEqualTo(fileStrategy.get("targetWeightSource"));

                List<Map<String, Object>> fileAxes = rawAxes(fileStrategy);
                assertThat(registration.axisNames())
                        .containsExactlyElementsOf(
                                fileAxes.stream().map(a -> String.valueOf(a.get("name"))).toList());

                for (Map<String, Object> fileAxis : fileAxes) {
                    ParameterAxis axis =
                            registration.axis(String.valueOf(fileAxis.get("name"))).orElseThrow();
                    assertThat(axis.searchValues())
                            .containsExactlyElementsOf((List<?>) fileAxis.get("searchValues"));
                    assertThat(axis.defaultValue()).isEqualTo(fileAxis.get("defaultValue"));
                    assertThat(axis.label()).isEqualTo(fileAxis.get("label"));
                    assertThat(axis.description()).isEqualTo(fileAxis.get("description"));
                    assertThat(axis.unit()).isEqualTo(fileAxis.get("unit"));

                    @SuppressWarnings("unchecked")
                    Map<String, Object> fileRange =
                            (Map<String, Object>) fileAxis.get("allowedRange");
                    if (fileRange == null) {
                        assertThat(axis.allowedRange()).isNull();
                    } else {
                        assertThat(axis.allowedRange().min()).isEqualTo(fileRange.get("min"));
                        assertThat(axis.allowedRange().max()).isEqualTo(fileRange.get("max"));
                        assertThat(axis.allowedRange().step()).isEqualTo(fileRange.get("step"));
                        assertThat(axis.allowedRange().values()).isEqualTo(fileRange.get("values"));
                    }
                }
            }
        }

        @Test
        @DisplayName("워밍업 규칙이 자원 파일과 같다")
        void warmupMatchesResourceFile() {
            for (Map<String, Object> fileStrategy : rawStrategies()) {
                StrategyRegistration registration =
                        registry.find(String.valueOf(fileStrategy.get("id"))).orElseThrow();

                @SuppressWarnings("unchecked")
                Map<String, Object> fileWarmup = (Map<String, Object>) fileStrategy.get("warmup");
                if (fileWarmup == null) {
                    assertThat(registration.warmup()).isNull();
                } else {
                    assertThat(registration.warmup().sum()).isEqualTo(fileWarmup.get("sum"));
                }
            }
        }

        @Test
        @DisplayName("분석 조건이 자원 파일과 같다")
        void analysisConditionsMatchResourceFile() {
            Map<String, Object> fileConditions = rawAnalysisConditions();

            assertCondition(
                    registry.analysisConditions().initialAmount(), fileConditions, "initialAmount");
            assertCondition(
                    registry.analysisConditions().targetVolatility(),
                    fileConditions,
                    "targetVolatility");
            assertCondition(
                    registry.analysisConditions().minEvaluationYears(),
                    fileConditions,
                    "minEvaluationYears");
        }

        @Test
        @DisplayName("전략마다 규칙 개수가 자원 파일과 같다")
        void ruleCountMatchesResourceFile() {
            for (Map<String, Object> fileStrategy : rawStrategies()) {
                String strategyId = String.valueOf(fileStrategy.get("id"));
                Object fileRules = fileStrategy.get("validationRules");
                int expected = fileRules == null ? 0 : ((List<?>) fileRules).size();

                assertThat(registry.rules(strategyId))
                        .as("%s의 규칙 개수", strategyId)
                        .hasSize(expected);
            }
        }

        @Test
        @DisplayName("같은 버전 안에서 축 순서와 후보값 인덱스가 흔들리지 않는다")
        void axisOrderAndValueIndicesAreStable() {
            FileParameterRegistry reloaded = newRegistry();

            assertThat(reloaded.version()).isEqualTo(registry.version());
            for (StrategyRegistration registration : registry.activeStrategies()) {
                StrategyRegistration other = reloaded.find(registration.strategyId()).orElseThrow();
                assertThat(other.axisNames()).isEqualTo(registration.axisNames());
                for (ParameterAxis axis : registration.axes()) {
                    // 인덱스가 흔들리면 저장된 grid_coordinates가 다른 값을 가리킨다(007 FR-029).
                    assertThat(other.axis(axis.name()).orElseThrow().searchValues())
                            .isEqualTo(axis.searchValues());
                }
            }
        }

        @Test
        @DisplayName("기본 시작값과 자동 탐색 후보값이 모두 허용 범위 안에 있다")
        void registeredValuesAreWithinAllowedRange() {
            for (StrategyRegistration registration : registry.activeStrategies()) {
                for (ParameterAxis axis : registration.axes()) {
                    String where = registration.strategyId() + "/" + axis.name();
                    assertThat(axis.defaultValue()).as("%s 기본값", where).isNotNull();
                    if (axis.allowedRange() == null) {
                        continue;
                    }
                    assertThat(axis.allowedRange().contains(axis.defaultValue()))
                            .as("%s 기본값이 허용 범위 안", where)
                            .isTrue();
                    if (axis.allowedRange().isEnumerated()) {
                        assertThat(axis.allowedRange().values())
                                .as("%s의 탐색 후보는 사용자 선택지의 부분집합", where)
                                .containsAll(axis.searchValues());
                    }
                }
            }
        }

        @Test
        @DisplayName("기준 전략 지정이 있으면 등록된 전략이다")
        void baselineStrategyIsRegistered() {
            registry.baselineStrategyId()
                    .ifPresent(
                            strategyId ->
                                    assertThat(registry.find(strategyId))
                                            .as("기준 전략이 등록표에 있어야 한다(007 FR-013d)")
                                            .isPresent());
        }

        @Test
        @DisplayName("승인된 제품 버전은 임시값으로 표시되지 않는다")
        void approvedVersionIsNotProvisional() {
            assertThat(registry.provisional()).isFalse();
        }

        @Test
        @DisplayName("임시 접두가 붙은 버전은 임시값으로 표시된다")
        void provisionalPrefixMarksItProvisional() {
            // 접두를 떼는 날 코드가 바뀌면 안 되므로 로더는 접두를 강제하지 않고 이 판정만 한다(내부 계약 §2.1).
            FileParameterRegistry synthetic =
                    load(
                            """
                            version: PROVISIONAL-FIXTURE
                            strategies: []
                            """);

            assertThat(synthetic.provisional()).isTrue();
        }
    }

    @Nested
    @DisplayName("활성 여부")
    class Activation {

        private final FileParameterRegistry synthetic =
                load(
                        """
                        version: FIXTURE-1
                        strategies:
                          - id: STRATEGY_ON
                            name: 활성 합성 전략
                            axes: []
                          - id: STRATEGY_OFF
                            name: 비활성 합성 전략
                            active: false
                            axes: []
                        """);

        @Test
        @DisplayName("비활성 전략은 활성 목록에서 빠지되 등록표에는 남는다")
        void inactiveStrategyIsHiddenButStillRegistered() {
            // 비활성 전략을 등록표에서 지우면 그 전략으로 돌린 과거 기록을 읽을 수 없다(research R5, 내부 계약 §2.3).
            assertThat(synthetic.activeStrategies())
                    .extracting(StrategyRegistration::strategyId)
                    .containsExactly("STRATEGY_ON");
            assertThat(synthetic.find("STRATEGY_OFF")).isPresent();
        }

        @Test
        @DisplayName("active를 적지 않으면 활성이다")
        void omittedActiveMeansActive() {
            assertThat(synthetic.find("STRATEGY_ON").orElseThrow().active()).isTrue();
        }
    }

    @Nested
    @DisplayName("워밍업 산출")
    class Warmup {

        private final FileParameterRegistry synthetic =
                load(
                        """
                        version: FIXTURE-1
                        strategies:
                          - id: SECTOR_MOMENTUM
                            name: 합성 모멘텀
                            warmup:
                              sum: [lookbackDays, skipRecentDays]
                            axes:
                              - name: lookbackDays
                                searchValues: [20, 60, 120]
                                defaultValue: 60
                              - name: skipRecentDays
                                searchValues: [0, 21]
                                defaultValue: 0
                          - id: STRATEGY_FLAT
                            name: 워밍업 없는 합성 전략
                            axes: []
                        """);

        @Test
        @DisplayName("축이 둘인 워밍업은 두 값을 모두 더한다")
        void sumsEveryAxisTerm() {
            // 둘째 항목을 읽지 않으면 60에 머문다. 첫 설계가 이 경우를 담지 못해 바꾼 것이다(research R4, quickstart 7-1).
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("lookbackDays", 60);
            values.put("skipRecentDays", 21);

            assertThat(synthetic.find("SECTOR_MOMENTUM").orElseThrow().warmup().evaluate(values))
                    .isEqualTo(81);
        }

        @Test
        @DisplayName("워밍업 규칙이 없는 전략은 규칙 자체가 없다 — 0으로 읽는 것은 부르는 쪽이다")
        void strategyWithoutWarmupHasNoRule() {
            assertThat(synthetic.find("STRATEGY_FLAT").orElseThrow().warmup()).isNull();
        }

        @Test
        @DisplayName("축 값이 없으면 워밍업을 지어내지 않고 거절한다")
        void refusesToInventMissingAxisValue() {
            var warmup = synthetic.find("SECTOR_MOMENTUM").orElseThrow().warmup();

            assertThatThrownBy(() -> warmup.evaluate(Map.of("lookbackDays", 60)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("skipRecentDays");
        }
    }

    @Nested
    @DisplayName("기동 검사")
    class StartupChecks {

        @Test
        @DisplayName("탐색 후보가 사용자 선택지를 벗어나면 기동하지 않는다")
        void rejectsSearchValueOutsideChoices() {
            assertThatThrownBy(
                            () ->
                                    load(
                                            """
                                            version: FIXTURE-1
                                            strategies:
                                              - id: STRATEGY_X
                                                name: 합성 전략
                                                axes:
                                                  - name: pick
                                                    searchValues: [1, 2, 9]
                                                    defaultValue: 1
                                                    allowedRange:
                                                      values: [1, 2, 3]
                                            """))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("subset")
                    .hasMessageContaining("STRATEGY_X/pick");
        }

        @Test
        @DisplayName("기본값이 허용 범위 밖이면 기동하지 않는다")
        void rejectsDefaultOutsideAllowedRange() {
            assertThatThrownBy(
                            () ->
                                    load(
                                            """
                                            version: FIXTURE-1
                                            strategies:
                                              - id: STRATEGY_X
                                                name: 합성 전략
                                                axes:
                                                  - name: weight
                                                    searchValues: [30]
                                                    defaultValue: 33
                                                    allowedRange:
                                                      min: 10
                                                      max: 100
                                                      step: 5
                                            """))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("defaultValue")
                    .hasMessageContaining("STRATEGY_X/weight");
        }

        @Test
        @DisplayName("목록형과 입력형을 동시에 채우면 어느 축인지 밝히며 기동하지 않는다")
        void rejectsAxisThatFillsBothRangeForms() {
            assertThatThrownBy(
                            () ->
                                    load(
                                            """
                                            version: FIXTURE-1
                                            strategies:
                                              - id: STRATEGY_X
                                                name: 합성 전략
                                                axes:
                                                  - name: lookbackDays
                                                    searchValues: [21]
                                                    defaultValue: 21
                                                    allowedRange:
                                                      min: 10
                                                      max: 504
                                                      step: 1
                                                      values: [21, 63]
                                            """))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("STRATEGY_X/lookbackDays");
        }

        @Test
        @DisplayName("분석 조건이 없으면 기동하지 않는다")
        void rejectsMissingAnalysisConditions() {
            Resource resource =
                    yaml(
                            """
                            version: FIXTURE-1
                            strategies: []
                            """);

            assertThatThrownBy(() -> new FileParameterRegistry(resource))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("analysisConditions");
        }

        @Test
        @DisplayName("분석 조건의 기본값이 범위 밖이면 기동하지 않는다")
        void rejectsAnalysisConditionDefaultOutsideRange() {
            Resource resource =
                    yaml(
                            """
                            version: FIXTURE-1
                            strategies: []
                            analysisConditions:
                              initialAmount:
                                min: 1000000
                                max: 1000000000
                                step: 1000000
                                default: 1500000
                              targetVolatility:
                                min: 5
                                max: 20
                                step: 1
                                default: 10
                              minEvaluationYears:
                                values: [0, 1, 3]
                                default: 1
                            """);

            assertThatThrownBy(() -> new FileParameterRegistry(resource))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("initialAmount");
        }
    }

    @Nested
    @DisplayName("유효성 판정")
    class Validation {

        private final FileParameterRegistry synthetic =
                load(
                        """
                        version: FIXTURE-1
                        strategies:
                          - id: STRATEGY_X
                            name: 합성 전략
                            axes:
                              - name: pick
                                searchValues: [1, 2, 3]
                                defaultValue: 2
                            validationRules:
                              - code: PICK_EXCEEDS_UNIVERSE
                                detail: 고른 수가 후보 수보다 많습니다.
                                left:
                                  axis: pick
                                operator: GREATER_THAN
                                right:
                                  context: UNIVERSE_SIZE
                          - id: STRATEGY_Y
                            name: 규칙 없는 합성 전략
                            axes: []
                        """);

        @Test
        @DisplayName("위반하면 등록표가 준 사유 코드를 그대로 돌려준다")
        void returnsRegistryReasonCode() {
            var verdict =
                    synthetic.validate("STRATEGY_X", combination("pick", 3), new SearchContext(2));

            assertThat(verdict.valid()).isFalse();
            assertThat(verdict.reasonCode()).isEqualTo("PICK_EXCEEDS_UNIVERSE");
            assertThat(verdict.reasonDetail()).isEqualTo("고른 수가 후보 수보다 많습니다.");
        }

        @Test
        @DisplayName("위반하지 않으면 유효다")
        void passesWhenRuleHolds() {
            assertThat(
                            synthetic
                                    .validate(
                                            "STRATEGY_X",
                                            combination("pick", 2),
                                            new SearchContext(2))
                                    .valid())
                    .isTrue();
        }

        @Test
        @DisplayName("규칙이 없는 전략은 모두 유효다 — 007이 규칙을 만들어 내지 않는다")
        void strategyWithoutRulesIsAlwaysValid() {
            assertThat(synthetic.validate("STRATEGY_Y", Map.of(), new SearchContext(1)).valid())
                    .isTrue();
        }

        @Test
        @DisplayName("등록되지 않은 전략의 판정 요청은 예외다 — 호출자가 먼저 걸러야 한다")
        void unknownStrategyThrows() {
            assertThatThrownBy(
                            () -> synthetic.validate("STRATEGY_Z", Map.of(), new SearchContext(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unknown strategy");
        }

        @Test
        @DisplayName("규칙 목록은 조건을 그대로 내보낸다 — 화면이 같은 조건을 평가한다")
        void exposesRuleConditions() {
            var rule = synthetic.rules("STRATEGY_X").get(0);

            assertThat(rule.code()).isEqualTo("PICK_EXCEEDS_UNIVERSE");
            assertThat(rule.left().axis()).isEqualTo("pick");
            assertThat(rule.right().context()).isEqualTo("UNIVERSE_SIZE");
        }
    }

    private static FileParameterRegistry newRegistry() {
        return new FileParameterRegistry(
                new ClassPathResource(FileParameterRegistry.RESOURCE_PATH));
    }

    /** 합성 등록표를 적재한다. 분석 조건은 필수라 따로 적지 않아도 되도록 여기서 붙인다. */
    private static FileParameterRegistry load(String content) {
        return new FileParameterRegistry(yaml(content + CONDITIONS));
    }

    private static void assertCondition(
            AnalysisCondition condition, Map<String, Object> fileConditions, String key) {
        @SuppressWarnings("unchecked")
        Map<String, Object> fileCondition = (Map<String, Object>) fileConditions.get(key);

        assertThat(condition.defaultValue())
                .as("%s 기본값", key)
                .isEqualTo(fileCondition.get("default"));
        assertThat(condition.unit()).as("%s 단위", key).isEqualTo(fileCondition.get("unit"));
        assertThat(condition.usedBy()).as("%s 사용처", key).isEqualTo(fileCondition.get("usedBy"));
        assertThat(condition.range().min()).isEqualTo(fileCondition.get("min"));
        assertThat(condition.range().max()).isEqualTo(fileCondition.get("max"));
        assertThat(condition.range().step()).isEqualTo(fileCondition.get("step"));
        assertThat(condition.range().values()).isEqualTo(fileCondition.get("values"));
    }

    private static boolean isActive(Map<String, Object> strategy) {
        Object active = strategy.get("active");
        return active == null || Boolean.TRUE.equals(active);
    }

    private static Map<String, Object> combination(String axis, Object value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(axis, value);
        return Map.copyOf(values);
    }

    private static Resource yaml(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
    }

    /** 로더와 같은 파일을 독립적으로 한 번 더 읽는다. 기대값을 테스트에 옮겨 적지 않기 위해서다. */
    private static Map<String, Object> rawDocument() {
        try (InputStream stream =
                new ClassPathResource(FileParameterRegistry.RESOURCE_PATH).getInputStream()) {
            return new Yaml().load(stream);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read registry resource", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rawStrategies() {
        return (List<Map<String, Object>>) rawDocument().get("strategies");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rawAnalysisConditions() {
        return (Map<String, Object>) rawDocument().get("analysisConditions");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rawAxes(Map<String, Object> strategy) {
        Object axes = strategy.get("axes");
        return axes == null ? new ArrayList<>() : (List<Map<String, Object>>) axes;
    }
}
