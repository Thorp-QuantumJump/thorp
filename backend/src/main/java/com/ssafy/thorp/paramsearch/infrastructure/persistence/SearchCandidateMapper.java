package com.ssafy.thorp.paramsearch.infrastructure.persistence;

import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import com.ssafy.thorp.paramsearch.domain.vo.GridCoordinates;
import com.ssafy.thorp.paramsearch.domain.vo.ParameterCombination;
import java.util.LinkedHashMap;

/** 도메인 모델과 JPA Entity 사이의 변환. */
final class SearchCandidateMapper {

    private SearchCandidateMapper() {}

    /**
     * 저장용 엔티티로 변환한다. {@code paramSearchId}를 인자로 받는 이유: 접수 시점에는 후보가 어느 묶음에 속하는지 모르는 상태로 만들어지고(묶음 저장이
     * 먼저 끝나야 PK가 나온다) 저장 직전에 붙는다.
     */
    static SearchCandidateJpaEntity toEntity(SearchCandidate candidate, Long paramSearchId) {
        return new SearchCandidateJpaEntity(
                candidate.getId(),
                paramSearchId,
                candidate.getCandidateNo(),
                candidate.getStrategyId(),
                new LinkedHashMap<>(candidate.getParameters().values()),
                new LinkedHashMap<>(candidate.getGridCoordinates().indices()),
                candidate.getBacktestJobId(),
                candidate.getStatus(),
                candidate.getReasonCode(),
                candidate.getReasonDetail(),
                candidate.getStability(),
                candidate.getRetryCount(),
                candidate.getPeriodPerformance(),
                candidate.getValidationLabel(),
                candidate.isSelected(),
                candidate.getSelectionReason());
    }

    static SearchCandidate toDomain(SearchCandidateJpaEntity entity) {
        return SearchCandidate.restore()
                .id(entity.getId())
                .paramSearchId(entity.getParamSearchId())
                .candidateNo(entity.getCandidateNo())
                .strategyId(entity.getStrategyId())
                .parameters(ParameterCombination.of(entity.getParameters()))
                .gridCoordinates(GridCoordinates.of(entity.getGridCoordinates()))
                .backtestJobId(entity.getBacktestJobId())
                .status(entity.getStatus())
                .reasonCode(entity.getReasonCode())
                .reasonDetail(entity.getReasonDetail())
                .stability(entity.getStability())
                .retryCount(entity.getRetryCount())
                .periodPerformance(entity.getPeriodPerformance())
                .validationLabel(entity.getValidationLabel())
                .selected(entity.isSelected())
                .selectionReason(entity.getSelectionReason())
                .build();
    }
}
