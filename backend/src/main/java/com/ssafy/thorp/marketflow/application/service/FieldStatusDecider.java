package com.ssafy.thorp.marketflow.application.service;

import com.ssafy.thorp.marketflow.domain.model.IndicatorCollectionState;
import com.ssafy.thorp.marketflow.domain.model.IndicatorMetadata;
import com.ssafy.thorp.marketflow.domain.vo.FieldStatus;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 수집 결과 자체로만 분야 상태를 판정한다. 대상월의 오래됨은 상태 판정에 사용하지 않는다. */
@Component
public class FieldStatusDecider {

    public FieldStatus decide(
            List<IndicatorMetadata> metadata, List<IndicatorCollectionState> collectionStates) {
        if (metadata.isEmpty()) {
            return FieldStatus.EMPTY;
        }
        Map<String, IndicatorCollectionState> states =
                collectionStates.stream()
                        .collect(
                                Collectors.toMap(
                                        IndicatorCollectionState::indicatorId,
                                        Function.identity(),
                                        (first, ignored) -> first));
        List<IndicatorCollectionState> relevant =
                metadata.stream().map(item -> states.get(item.indicatorId())).toList();
        if (relevant.stream().anyMatch(state -> state == null || !state.successful())) {
            return FieldStatus.COLLECTION_FAILED;
        }
        if (relevant.stream().anyMatch(IndicatorCollectionState::exampleData)) {
            return FieldStatus.EXAMPLE;
        }
        return FieldStatus.READY;
    }
}
