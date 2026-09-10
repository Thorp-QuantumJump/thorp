package com.ssafy.thorp.paramsearch.infrastructure.persistence;

import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import com.ssafy.thorp.paramsearch.domain.model.SearchCounts;
import com.ssafy.thorp.paramsearch.domain.model.SelectionRule;
import com.ssafy.thorp.paramsearch.domain.vo.ParamSearchId;
import com.ssafy.thorp.paramsearch.domain.vo.SearchSpace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 도메인 모델과 JPA Entity 사이의 변환. 메서드는 이 둘로 제한한다({@code BacktestJobMapper}와 같은 규약).
 *
 * <p><b>JSONB로 가는 값의 변환을 여기서 한다.</b> 도메인의 {@code SearchSpace}·{@code SelectionRule}·{@code
 * SearchCounts}는 {@code Map} 변환만 제공하고 Jackson을 모른다 — 도메인 계층이 프레임워크에 의존할 수 없기 때문이다({@code
 * ArchitectureTest}). 실제 직렬화는 Hibernate의 JSON 매핑이 이 {@code Map}을 받아 처리한다.
 */
final class ParamSearchMapper {

    private ParamSearchMapper() {}

    static ParamSearchJpaEntity toEntity(ParamSearch paramSearch) {
        return new ParamSearchJpaEntity(
                paramSearch.getId(),
                paramSearch.getSearchId() == null ? null : paramSearch.getSearchId().value(),
                paramSearch.getOwnerUserId(),
                paramSearch.getStrategyIds(),
                paramSearch.getInstrumentIds(),
                paramSearch.getRegistryVersion(),
                toSearchSpaceMap(paramSearch.getSearchSpace()),
                paramSearch.getEvaluationSplit(),
                paramSearch.getSearchMethod(),
                paramSearch.getRandomSeed(),
                paramSearch.getSelectionRule().toMap(),
                paramSearch.getLimits(),
                paramSearch.getReferenceVersions(),
                paramSearch.getBaselineJobId(),
                paramSearch.getStatus(),
                paramSearch.getStopReason(),
                paramSearch.getCounts().toMap(),
                paramSearch.getSelection(),
                paramSearch.isDataVersionChanged(),
                paramSearch.getStartedAt(),
                paramSearch.getFinishedAt());
    }

    static ParamSearch toDomain(ParamSearchJpaEntity entity) {
        return ParamSearch.restore()
                .id(entity.getId())
                .searchId(ParamSearchId.from(entity.getSearchId()))
                .ownerUserId(entity.getOwnerUserId())
                .strategyIds(entity.getStrategyIds())
                .instrumentIds(entity.getInstrumentIds())
                .registryVersion(entity.getRegistryVersion())
                .searchSpace(toSearchSpace(entity.getSearchSpace()))
                .evaluationSplit(entity.getEvaluationSplit())
                .searchMethod(entity.getSearchMethod())
                .randomSeed(entity.getRandomSeed())
                .selectionRule(SelectionRule.fromMap(entity.getSelectionRule()))
                .limits(entity.getLimits())
                .referenceVersions(entity.getReferenceVersions())
                .baselineJobId(entity.getBaselineJobId())
                .status(entity.getStatus())
                .stopReason(entity.getStopReason())
                .counts(SearchCounts.fromMap(entity.getCounts()))
                .selection(entity.getSelection())
                .dataVersionChanged(entity.isDataVersionChanged())
                .startedAt(entity.getStartedAt())
                .finishedAt(entity.getFinishedAt())
                .build();
    }

    private static Map<String, List<Object>> toSearchSpaceMap(SearchSpace searchSpace) {
        return new LinkedHashMap<>(searchSpace.axes());
    }

    /**
     * JSONB에서 읽은 축 목록을 {@link SearchSpace}로 되돌린다.
     *
     * <p>축마다 리스트를 새로 담는 이유: Jackson이 준 리스트를 그대로 {@code SearchSpace}에 넘기면 저장소가 준 가변 리스트가 도메인 VO 안으로
     * 들어간다. {@code SearchSpace}가 다시 불변 복사를 하지만, 복사 전 참조를 공유하지 않도록 여기서 끊는다.
     */
    private static SearchSpace toSearchSpace(Map<String, List<Object>> stored) {
        Map<String, List<Object>> axes = new LinkedHashMap<>();
        stored.forEach((axis, values) -> axes.put(axis, new ArrayList<>(values)));
        return SearchSpace.of(axes);
    }
}
