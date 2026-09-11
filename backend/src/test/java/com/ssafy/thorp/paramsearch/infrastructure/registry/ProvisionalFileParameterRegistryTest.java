package com.ssafy.thorp.paramsearch.infrastructure.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.paramsearch.application.port.ParameterAxis;
import com.ssafy.thorp.paramsearch.application.port.SearchContext;
import com.ssafy.thorp.paramsearch.application.port.StrategyRegistration;
import com.ssafy.thorp.paramsearch.domain.vo.ParameterCombination;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
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
 * 임시 등록표 로더 검증(T029).
 *
 * <p><b>기대값을 이 테스트에 적지 않는다.</b> 전략·축·후보값을 테스트에 옮겨 적으면 그 순간 전략 데이터가 두 곳에 생기고, 자원 파일을 고쳐도 테스트가
 * 통과하는(또는 이유 없이 깨지는) 상태가 된다. 그래서 자원 파일을 따로 한 번 더 읽어 로더의 결과와 대조한다 — 확인하는 것은 값 자체가 아니라 <b>파일과 포트 결과가
 * 같다는 것</b>이다(FR-025).
 *
 * <p>규칙 평가는 합성 등록표로 본다. 실제 파일의 규칙에 의존하면 규칙이 바뀔 때마다 테스트가 따라 바뀐다.
 */
class ProvisionalFileParameterRegistryTest {

    private final ProvisionalFileParameterRegistry registry = newRegistry();

    @Nested
    @DisplayName("자원 파일 적재")
    class Loading {

        @Test
        @DisplayName("버전은 임시임을 드러내는 접두로 시작한다")
        void versionMarksItProvisional() {
            assertThat(registry.version()).startsWith("PROVISIONAL-");
        }

        @Test
        @DisplayName("전략·축·후보값·기본값·허용 범위가 자원 파일과 같다")
        void matchesResourceFile() {
            List<Map<String, Object>> fileStrategies = rawStrategies();

            assertThat(registry.activeStrategies())
                    .extracting(StrategyRegistration::strategyId)
                    .containsExactlyElementsOf(
                            fileStrategies.stream().map(s -> String.valueOf(s.get("id"))).toList());

            for (Map<String, Object> fileStrategy : fileStrategies) {
                StrategyRegistration registration =
                        registry.find(String.valueOf(fileStrategy.get("id"))).orElseThrow();
                assertThat(registration.name()).isEqualTo(fileStrategy.get("name"));

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
                    }
                }
            }
        }

        @Test
        @DisplayName("같은 버전 안에서 축 순서와 후보값 인덱스가 흔들리지 않는다")
        void axisOrderAndValueIndicesAreStable() {
            ProvisionalFileParameterRegistry reloaded = newRegistry();

            assertThat(reloaded.version()).isEqualTo(registry.version());
            for (StrategyRegistration registration : registry.activeStrategies()) {
                StrategyRegistration other = reloaded.find(registration.strategyId()).orElseThrow();
                assertThat(other.axisNames()).isEqualTo(registration.axisNames());
                for (ParameterAxis axis : registration.axes()) {
                    // 인덱스가 흔들리면 저장된 grid_coordinates가 다른 값을 가리킨다(FR-029).
                    assertThat(other.axis(axis.name()).orElseThrow().searchValues())
                            .isEqualTo(axis.searchValues());
                }
            }
        }

        @Test
        @DisplayName("기본 시작값은 자동 탐색 후보값이나 허용 범위 안에 있다")
        void defaultValueIsWithinRegisteredBounds() {
            for (StrategyRegistration registration : registry.activeStrategies()) {
                for (ParameterAxis axis : registration.axes()) {
                    assertThat(axis.defaultValue())
                            .as("%s/%s 기본값", registration.strategyId(), axis.name())
                            .isNotNull();
                    if (axis.allowedRange() == null || axis.allowedRange().min() == null) {
                        continue;
                    }
                    assertThat(decimal(axis.defaultValue()))
                            .as("%s/%s 기본값이 허용 범위 안", registration.strategyId(), axis.name())
                            .isBetween(
                                    decimal(axis.allowedRange().min()),
                                    decimal(axis.allowedRange().max()));
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
                                            .as("기준 전략이 등록표에 있어야 한다(FR-013d)")
                                            .isPresent());
        }

        @Test
        @DisplayName("임시 접두가 없는 버전은 적재를 거부한다")
        void rejectsVersionWithoutProvisionalPrefix() {
            Resource resource =
                    yaml(
                            """
                            version: 1.0.0
                            strategies: []
                            """);

            assertThatThrownBy(() -> new ProvisionalFileParameterRegistry(resource))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PROVISIONAL-");
        }
    }

    @Nested
    @DisplayName("유효성 판정")
    class Validation {

        private final ProvisionalFileParameterRegistry synthetic =
                new ProvisionalFileParameterRegistry(
                        yaml(
                                """
                                version: PROVISIONAL-TEST
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
                                """));

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
            assertThat(
                            synthetic
                                    .validate(
                                            "STRATEGY_Y",
                                            ParameterCombination.of(Map.of()),
                                            new SearchContext(1))
                                    .valid())
                    .isTrue();
        }

        @Test
        @DisplayName("등록되지 않은 전략의 판정 요청은 예외다 — 호출자가 먼저 걸러야 한다")
        void unknownStrategyThrows() {
            assertThatThrownBy(
                            () ->
                                    synthetic.validate(
                                            "STRATEGY_Z",
                                            ParameterCombination.of(Map.of()),
                                            new SearchContext(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unknown strategy");
        }
    }

    private static ProvisionalFileParameterRegistry newRegistry() {
        return new ProvisionalFileParameterRegistry(
                new ClassPathResource(ProvisionalFileParameterRegistry.RESOURCE_PATH));
    }

    private static ParameterCombination combination(String axis, Object value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(axis, value);
        return ParameterCombination.of(values);
    }

    private static BigDecimal decimal(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    private static Resource yaml(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
    }

    /** 로더와 같은 파일을 독립적으로 한 번 더 읽는다. 기대값을 테스트에 옮겨 적지 않기 위해서다. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rawStrategies() {
        try (InputStream stream =
                new ClassPathResource(ProvisionalFileParameterRegistry.RESOURCE_PATH)
                        .getInputStream()) {
            Map<String, Object> document = new Yaml().load(stream);
            return (List<Map<String, Object>>) document.get("strategies");
        } catch (IOException e) {
            throw new IllegalStateException("cannot read registry resource", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rawAxes(Map<String, Object> strategy) {
        Object axes = strategy.get("axes");
        return axes == null ? new ArrayList<>() : (List<Map<String, Object>>) axes;
    }
}
