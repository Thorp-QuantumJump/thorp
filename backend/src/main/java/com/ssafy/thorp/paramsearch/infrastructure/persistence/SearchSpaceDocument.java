package com.ssafy.thorp.paramsearch.infrastructure.persistence;

import java.util.List;

/**
 * {@code param_search.search_space} JSONB의 전략 한 건. 도메인 {@code SearchSpace.StrategySpace}의 저장 표현이며
 * 변환은 {@link ParamSearchMapper}가 한다.
 *
 * <p><b>축을 배열로 담는다.</b> {@code jsonb}는 객체 키 순서를 보존하지 않으므로 축을 객체 키로 두면 저장된 그리드 좌표가 다른 축을 가리킨다
 * (data-model.md §2.1, FR-029).
 *
 * <p>도메인 VO를 그대로 저장하지 않는 이유는 나머지 JSONB 컬럼과 같다 — 도메인은 직렬화 형태를 모르고, 저장 표현이 바뀌어도 도메인 불변식이 흔들리지 않아야 한다.
 */
public record SearchSpaceDocument(String strategyName, List<AxisDocument> axes) {

    /**
     * 축 한 개. {@code values}의 인덱스가 {@code grid_coordinates}의 인덱스다.
     *
     * <p>값 자리를 {@code Object}로 두되 리스트 타입은 명시한다. 컬렉션 자체를 {@code Object}로 선언하면 Jackson이 untyped 경로를
     * 타는데, 클래스패스에 {@code jackson-module-scala}가 있으면(테스트에서 {@code spring-kafka-test}가 끌어온다) JSON 배열이
     * Scala {@code List}로 돌아온다.
     */
    public record AxisDocument(String name, String label, String unit, List<Object> values) {}
}
