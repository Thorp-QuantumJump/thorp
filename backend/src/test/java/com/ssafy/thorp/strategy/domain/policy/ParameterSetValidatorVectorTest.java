package com.ssafy.thorp.strategy.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.strategy.domain.model.ParameterViolation;
import com.ssafy.thorp.strategy.domain.model.ValidationOutcome;
import com.ssafy.thorp.strategy.domain.vo.ParameterValue;
import com.ssafy.thorp.strategy.support.TestVectorLoader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 공유 테스트 벡터로 판정기를 검증한다. FE(vitest)가 같은 파일로 같은 검증을 하며, 두 결과가 같아야 SC-002·SC-010이 충족된다.
 *
 * <p>비교 대상은 위반의 {@code code}·{@code parameterName}·<b>배열 순서</b>, 그리고 확정 값 집합과 이력 요구량이다. 문구는 비교하지
 * 않는다 — 문구를 다듬을 때마다 두 구현을 함께 고칠 이유가 없다.
 */
class ParameterSetValidatorVectorTest {

    private static final TestVectorLoader.Vector PARAMETER_VALIDATION =
            TestVectorLoader.load("parameter-validation.json");
    private static final TestVectorLoader.Vector EXECUTION_SCOPE =
            TestVectorLoader.load("execution-scope.json");

    private static Stream<Arguments> parameterValidationCases() {
        return toArguments(PARAMETER_VALIDATION);
    }

    private static Stream<Arguments> executionScopeCases() {
        return toArguments(EXECUTION_SCOPE);
    }

    private static Stream<Arguments> toArguments(TestVectorLoader.Vector vector) {
        return vector.cases().stream()
                .map(vectorCase -> Arguments.of(vectorCase.name(), vector, vectorCase));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parameterValidationCases")
    @DisplayName("파라미터 값 판정 벡터")
    void parameterValidationVector(
            String name, TestVectorLoader.Vector vector, TestVectorLoader.VectorCase given) {
        verify(vector, given);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionScopeCases")
    @DisplayName("실행 목적 필터링 벡터")
    void executionScopeVector(
            String name, TestVectorLoader.Vector vector, TestVectorLoader.VectorCase given) {
        verify(vector, given);
    }

    private static void verify(TestVectorLoader.Vector vector, TestVectorLoader.VectorCase given) {
        ValidationOutcome outcome =
                ParameterSetValidator.validate(
                        vector.catalog(),
                        given.strategyId(),
                        given.executionScope(),
                        given.input());

        assertThat(outcome.valid()).as("valid").isEqualTo(given.expectedValid());
        assertThat(codesAndNames(outcome.violations()))
                .as("위반 목록(코드·대상·순서)")
                .containsExactlyElementsOf(given.expectedViolations());
        assertThat(outcome.requiredHistoryBars())
                .as("최소 이력 요구량")
                .isEqualTo(given.expectedHistoryBars());
        assertResolved(given, outcome);
    }

    private static void assertResolved(
            TestVectorLoader.VectorCase given, ValidationOutcome outcome) {
        if (given.expectedResolved() == null) {
            assertThat(outcome.resolvedParameters()).as("확정 값 집합").isNull();
            return;
        }

        Map<String, Object> actual = new LinkedHashMap<>();
        outcome.resolved()
                .orElseThrow()
                .values()
                .forEach((name, value) -> actual.put(name, rawOf(value)));

        assertThat(actual.keySet())
                .as("확정 값 집합의 파라미터")
                .containsExactlyInAnyOrderElementsOf(given.expectedResolved().keySet());
        given.expectedResolved()
                .forEach(
                        (name, expected) ->
                                assertThat(TestVectorLoader.valuesEqual(expected, actual.get(name)))
                                        .as(
                                                "확정 값 %s: 기대=%s, 실제=%s",
                                                name, expected, actual.get(name))
                                        .isTrue());
    }

    private static Object rawOf(ParameterValue value) {
        return value.raw();
    }

    private static List<TestVectorLoader.ExpectedViolation> codesAndNames(
            List<ParameterViolation> violations) {
        return violations.stream()
                .map(
                        violation ->
                                new TestVectorLoader.ExpectedViolation(
                                        violation.code().name(), violation.parameterName()))
                .toList();
    }
}
