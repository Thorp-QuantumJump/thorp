package com.ssafy.thorp.strategy.infrastructure.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;

/**
 * 전략 정의 JSON의 Jackson 바인딩 전용 표현.
 *
 * <p>도메인 record가 직접 Jackson을 쓰지 못하기 때문에(도메인은 프레임워크를 참조하지 않는다) 역직렬화는 여기서만 한다. 이 DTO는 <b>검증하지 않는다</b>
 * — 값의 타당성은 {@link StrategyDefinitionJsonMapper}가 도메인 record로 옮길 때 도메인 불변식이 판정한다. 여기서 함께 검증하면 같은
 * 규칙이 두 곳에 생긴다.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = false)}인 이유: 오타 난 필드가 조용히 무시되면 정의 작성자는 값을 넣었다고
 * 믿는데 실제로는 기본값이 쓰인다. 계약에 없는 필드는 거부한다.
 *
 * <p>{@code defaultValue}만 {@link JsonNode}인 이유: 이 자리에 올 수 있는 타입이 파라미터 {@code type}에 따라 달라진다. 숫자를
 * {@code double}로 받으면 0.1이 이진 오차와 함께 들어와 간격(step) 판정이 깨지므로, 노드 상태로 받아 타입을 보고 옮긴다.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record StrategyDefinitionJsonDto(
        String schemaVersion,
        String strategyId,
        Integer definitionVersion,
        String displayName,
        String description,
        String entryCondition,
        String exitCondition,
        List<String> executionScopes,
        List<ParameterDto> parameters,
        List<ConstraintDto> parameterConstraints,
        HistoryRequirementDto minimumHistoryRequirement,
        List<ChangeDto> changeHistory,
        Boolean deprecated) {

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ParameterDto(
            String name,
            String displayName,
            String description,
            String type,
            Boolean required,
            JsonNode defaultValue,
            String unit,
            List<String> executionScopes,
            NumericRangeDto numericRange,
            List<EnumOptionDto> enumValues) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record NumericRangeDto(
            BigDecimal min,
            Boolean minInclusive,
            BigDecimal max,
            Boolean maxInclusive,
            BigDecimal step) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record EnumOptionDto(String value, String displayName) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ConstraintDto(String left, String operator, String right, String message) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record HistoryRequirementDto(
            String rule, Integer value, List<String> parameters, Integer offset, String unit) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ChangeDto(
            Integer definitionVersion, String changeType, String changeSummary, String changedAt) {}
}
