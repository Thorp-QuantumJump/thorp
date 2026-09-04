package com.ssafy.thorp.strategy.domain.model;

import com.ssafy.thorp.strategy.domain.vo.ExecutionScope;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 하나의 전략을 완전히 기술하는 단위. 메타데이터와 검증 계약이며 매매 판단 로직은 담지 않는다.
 *
 * <p>진입·청산 조건은 <b>사람이 읽는 서술</b>로만 담는다(FR-003). 기계가 해석·실행할 수 있는 조건식을 넣으려면 조건 언어와 해석기를 만들어야 하고, 그렇게
 * 하면 그 언어로 표현할 수 없는 전략이 나타나는 순간 확장성이 오히려 떨어진다. 판단 로직은 전략 구현이 소유하며, 서술과 실제 동작이 어긋나면 서술이 기준이고 그 불일치는
 * 구현의 결함으로 다룬다(FR-003a).
 *
 * <p>파라미터는 개수와 이름이 전략마다 다르지만 항상 {@link ParameterDefinition} 목록이다. 파라미터가 하나도 없는 전략도 빈 목록으로 표현되며 오류가
 * 아니다(FR-004). 파라미터 사이의 제약은 개별 파라미터의 범위와 분리된 목록으로 따로 담는다(FR-009b).
 *
 * <p>이 record는 자기 접근자가 성립하는 데 필요한 구조만 지킨다. 파라미터 이름이 유일해야 한다는 것은 {@link #parameter(String)}이 하나의 답을
 * 가지려면 필요하므로 여기서 막는다. 그 밖의 정의 형식 규칙(기본값이 범위 안인지, 제약이 참조하는 이름이 존재하는지, 변경 이력이 정렬돼 있는지 등)은 카탈로그 등재 시점의
 * 형식 검증이 다룬다.
 *
 * @param schemaVersion 정의 형식의 버전. 개별 전략의 {@code definitionVersion}과 다른 축이다
 * @param strategyId 카탈로그 안에서 유일한 식별자
 * @param definitionVersion 전략별 단조 증가 정수
 * @param displayName 화면에 보이는 이름
 * @param description 이 전략이 무엇을 하는지
 * @param entryCondition 진입 조건 서술
 * @param exitCondition 청산 조건 서술
 * @param executionScopes 이 전략이 유효한 실행 목적
 * @param parameters 조정 가능한 파라미터. 비어 있을 수 있다
 * @param parameterConstraints 파라미터 사이의 제약. 비어 있을 수 있다
 * @param minimumHistoryRequirement 최소 이력 요구량 산출 규칙
 * @param changeHistory 변경 이력. 최신 우선
 * @param deprecated 신규 선택 대상에서 제외됐는지
 */
public record StrategyDefinition(
        String schemaVersion,
        String strategyId,
        int definitionVersion,
        String displayName,
        String description,
        String entryCondition, // 설명
        String exitCondition, // 설명
        Set<ExecutionScope> executionScopes,
        List<ParameterDefinition> parameters,
        List<ParameterConstraint> parameterConstraints,
        MinimumHistoryRequirement minimumHistoryRequirement,
        List<ChangeHistoryEntry> changeHistory,
        boolean deprecated) {

    /** 계약의 전략 식별자 규칙. */
    public static final Pattern STRATEGY_ID_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{2,49}$");

    private static final Pattern SCHEMA_VERSION_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");

    public StrategyDefinition {
        Objects.requireNonNull(strategyId, "전략 식별자는 null일 수 없다");
        Objects.requireNonNull(executionScopes, "전략의 실행 목적은 null일 수 없다");
        Objects.requireNonNull(parameters, "파라미터 목록은 null일 수 없다(비어 있을 수는 있다)");
        Objects.requireNonNull(parameterConstraints, "제약 목록은 null일 수 없다(비어 있을 수는 있다)");
        Objects.requireNonNull(minimumHistoryRequirement, "최소 이력 규칙은 null일 수 없다");
        Objects.requireNonNull(changeHistory, "변경 이력은 null일 수 없다");

        if (!STRATEGY_ID_PATTERN.matcher(strategyId).matches()) {
            throw new IllegalArgumentException(
                    "전략 식별자가 규칙에 맞지 않는다(대문자·숫자·밑줄, 3~50자): " + strategyId);
        }
        if (schemaVersion == null || !SCHEMA_VERSION_PATTERN.matcher(schemaVersion).matches()) {
            throw new IllegalArgumentException("형식 버전이 규칙에 맞지 않는다(x.y.z): " + schemaVersion);
        }
        if (definitionVersion < 1) {
            throw new IllegalArgumentException("정의 버전은 1 이상이어야 한다: " + definitionVersion);
        }
        requireText(displayName, "표시 이름", strategyId);
        requireText(description, "설명", strategyId);
        requireText(entryCondition, "진입 조건 서술", strategyId);
        requireText(exitCondition, "청산 조건 서술", strategyId);

        if (executionScopes.isEmpty()) {
            throw new IllegalArgumentException("전략의 실행 목적은 하나 이상이어야 한다: " + strategyId);
        }
        if (changeHistory.isEmpty()) {
            throw new IllegalArgumentException("변경 이력은 하나 이상이어야 한다: " + strategyId);
        }

        // parameter(name)이 하나의 답을 갖기 위한 구조 가드. 이름이 겹치면 어느 쪽이 맞는지
        // 정의 자체가 답하지 못하므로 임의로 고르지 않고 거부한다.
        Set<String> seen = new LinkedHashSet<>();
        for (ParameterDefinition parameter : parameters) {
            if (!seen.add(parameter.name())) {
                throw new IllegalArgumentException(
                        "파라미터 이름이 중복됐다: strategyId=%s, name=%s"
                                .formatted(strategyId, parameter.name()));
            }
            // 전략이 지원하지 않는 실행 목적을 파라미터가 주장하면 그 파라미터는 어느 요청에서도
            // 판정되지 않는다. 사라진 이유를 정의만 보고는 알 수 없으므로 정의 단계에서 막는다.
            if (!executionScopes.containsAll(parameter.executionScopes())) {
                throw new IllegalArgumentException(
                        "파라미터의 실행 목적이 전략의 실행 목적을 벗어난다: strategyId=%s, name=%s, 파라미터=%s, 전략=%s"
                                .formatted(
                                        strategyId,
                                        parameter.name(),
                                        parameter.executionScopes(),
                                        executionScopes));
            }
        }

        executionScopes = Set.copyOf(executionScopes);
        parameters = List.copyOf(parameters);
        parameterConstraints = List.copyOf(parameterConstraints);
        changeHistory = List.copyOf(changeHistory);
    }

    private static void requireText(String value, String label, String strategyId) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "전략의 %s은 비어 있을 수 없다: %s".formatted(label, strategyId));
        }
    }

    /** 이름으로 파라미터 정의를 찾는다. */
    public Optional<ParameterDefinition> parameter(String name) {
        return parameters.stream().filter(it -> it.name().equals(name)).findFirst();
    }

    /** 정의된 파라미터 이름. 선언 순서를 유지한다. */
    public Set<String> parameterNames() {
        return Collections.unmodifiableSet(
                new LinkedHashSet<>(parameters.stream().map(ParameterDefinition::name).toList()));
    }

    /**
     * 주어진 실행 목적에서 의미를 갖는 파라미터만 선언 순서대로 반환한다(FR-021).
     *
     * <p>범위 밖 파라미터는 그 실행 목적에서 존재하지 않는 것으로 취급한다. 그래야 과거 검증에만 쓰이는 값(예: 슬리피지)을 실시장 운용에서 요구하지 않는다.
     */
    public List<ParameterDefinition> parametersFor(ExecutionScope scope) {
        return parameters.stream().filter(it -> it.appliesTo(scope)).toList();
    }

    /** 이 전략이 주어진 실행 목적을 지원하는지. */
    public boolean supports(ExecutionScope scope) {
        return executionScopes.contains(scope);
    }

    public boolean hasParameters() {
        return !parameters.isEmpty();
    }

    /** 변경 이력의 첫 항목. 이력이 최신 우선으로 정렬돼 있는지는 형식 검증이 확인한다. */
    public ChangeHistoryEntry latestChange() {
        return changeHistory.get(0);
    }
}
