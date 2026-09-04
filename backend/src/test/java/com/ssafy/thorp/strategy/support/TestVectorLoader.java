package com.ssafy.thorp.strategy.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.strategy.domain.model.StrategyCatalog;
import com.ssafy.thorp.strategy.domain.model.StrategyDefinition;
import com.ssafy.thorp.strategy.domain.vo.ExecutionScope;
import com.ssafy.thorp.strategy.infrastructure.catalog.StrategyDefinitionJsonDto;
import com.ssafy.thorp.strategy.infrastructure.catalog.StrategyDefinitionJsonMapper;
import com.ssafy.thorp.strategy.presentation.request.StrategyConfigRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BE와 FE가 공유하는 판정 테스트 벡터를 읽는다.
 *
 * <p>벡터의 정본은 {@code docs/specs/strategy-definition/test-vectors/}에 있고 FE(vitest)도 <b>같은 파일</b>을
 * 읽는다. 사본을 만들지 않는 이유가 이 파일의 존재 이유다 — 사본을 두는 순간 "두 구현이 같은 판정을 낸다"는 증거가 사라진다.
 *
 * <p>{@code definitionSources[].ref}는 배포 정의 루트({@code backend/src/main/resources/}) 기준 상대 경로다.
 * {@code inline}은 그 자리에 정의 전체가 담긴 합성 전략으로, 실제 배포에는 없지만 판정 경로를 확인하는 데 필요한 것들이다.
 */
public final class TestVectorLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path VECTOR_DIR =
            Path.of("..", "docs", "specs", "strategy-definition", "test-vectors");
    private static final Path DEFINITION_ROOT = Path.of("src", "main", "resources");

    private TestVectorLoader() {}

    /** 벡터 파일 하나. 케이스가 참조하는 전략은 모두 {@code catalog}에 들어 있다. */
    public record Vector(StrategyCatalog catalog, List<VectorCase> cases) {}

    /**
     * 케이스 하나.
     *
     * @param input 사용자가 보낸 값. 실제 요청과 같은 경로로 변환하려고 {@link StrategyConfigRequest}를 거친다
     * @param expectedViolations 코드와 대상 파라미터, 그리고 <b>배열 순서</b>까지 비교 대상이다
     */
    public record VectorCase(
            String name,
            String strategyId,
            ExecutionScope executionScope,
            Map<String, Object> input,
            boolean expectedValid,
            List<ExpectedViolation> expectedViolations,
            Map<String, Object> expectedResolved,
            Integer expectedHistoryBars) {}

    /** 기대 위반. 문구(message)는 비교하지 않는다 — 문구를 다듬을 때마다 두 구현을 함께 고칠 이유가 없다. */
    public record ExpectedViolation(String code, String parameterName) {}

    public static Vector load(String fileName) {
        JsonNode root = read(VECTOR_DIR.resolve(fileName));

        List<StrategyDefinition> definitions = new ArrayList<>();
        root.get("definitionSources")
                .properties()
                .forEach(entry -> definitions.add(toDefinition(entry.getKey(), entry.getValue())));

        List<VectorCase> cases = new ArrayList<>();
        root.get("cases").forEach(node -> cases.add(toCase(node)));
        return new Vector(StrategyCatalog.of(definitions), cases);
    }

    private static StrategyDefinition toDefinition(String strategyId, JsonNode source) {
        JsonNode definitionNode =
                source.has("inline")
                        ? source.get("inline")
                        : read(DEFINITION_ROOT.resolve(source.get("ref").textValue()));
        return StrategyDefinitionJsonMapper.toDomain(
                MAPPER.convertValue(definitionNode, StrategyDefinitionJsonDto.class), strategyId);
    }

    private static VectorCase toCase(JsonNode node) {
        JsonNode expected = node.get("expected");
        List<ExpectedViolation> violations = new ArrayList<>();
        expected.get("violations")
                .forEach(
                        violation ->
                                violations.add(
                                        new ExpectedViolation(
                                                violation.get("code").textValue(),
                                                text(violation.get("parameterName")))));

        JsonNode resolved = expected.get("resolvedParameters");
        JsonNode bars = expected.get("requiredHistoryBars");
        return new VectorCase(
                node.get("name").textValue(),
                node.get("strategyId").textValue(),
                node.hasNonNull("executionScope")
                        ? ExecutionScope.valueOf(node.get("executionScope").textValue())
                        : ExecutionScope.BACKTEST,
                toValues(node.get("input")),
                expected.get("valid").booleanValue(),
                violations,
                resolved == null || resolved.isNull() ? null : toValues(resolved),
                bars == null || bars.isNull() ? null : bars.intValue());
    }

    /** JSON 객체를 요청 본문과 같은 경로로 변환한다. 테스트만의 변환 규칙을 두면 벡터가 검증하는 대상이 달라진다. */
    private static Map<String, Object> toValues(JsonNode object) {
        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        object.properties().forEach(entry -> nodes.put(entry.getKey(), entry.getValue()));
        return new StrategyConfigRequest("PLACEHOLDER", ExecutionScope.BACKTEST, nodes)
                .toCommand()
                .parameters();
    }

    /**
     * 값이 같은지 비교한다. 숫자는 {@code 30}과 {@code 30.0}을 같게 본다 — 벡터는 사람이 쓰는 JSON이라 스케일을 강제할 수 없고, 스케일 차이로
     * 실패하는 테스트는 판정 규칙에 대해 아무것도 알려 주지 않는다.
     */
    public static boolean valuesEqual(Object expected, Object actual) {
        if (expected instanceof Number expectedNumber && actual instanceof Number actualNumber) {
            return new BigDecimal(expectedNumber.toString())
                            .compareTo(new BigDecimal(actualNumber.toString()))
                    == 0;
        }
        return expected == null ? actual == null : expected.equals(actual);
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.textValue();
    }

    private static JsonNode read(Path path) {
        try {
            return MAPPER.readTree(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException("테스트 벡터를 읽지 못했다: " + path.toAbsolutePath(), e);
        }
    }
}
