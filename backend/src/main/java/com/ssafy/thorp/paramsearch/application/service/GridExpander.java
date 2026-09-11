package com.ssafy.thorp.paramsearch.application.service;

import com.ssafy.thorp.paramsearch.domain.vo.GridCoordinates;
import com.ssafy.thorp.paramsearch.domain.vo.ParameterCombination;
import com.ssafy.thorp.paramsearch.domain.vo.SearchSpace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 탐색 공간을 전체 그리드로 펼치고 좌표를 부여한다(FR-005).
 *
 * <p><b>전략별로</b> 펼친다. 축 개수와 축별 후보 개수가 전략마다 달라도 같은 절차로 처리하고 전체 조합 수는 전략별 곱의 합이다(FR-027). 이 클래스에
 * {@code strategyId} 분기는 없다 — 전략이 늘어나도 이 코드는 바뀌지 않는다(FR-025).
 *
 * <p><b>전개 순서를 고정한다.</b> 전략은 탐색 공간에 담긴 순서, 조합은 마지막 축이 가장 빨리 도는 순서(자리수 올림과 같다)다. 같은 등록표 버전과 같은 선택
 * 범위가 항상 같은 후보 집합과 같은 순번을 만들어야 재현 기록이 성립한다(FR-029).
 *
 * <p>좌표는 각 축 값 목록에서의 인덱스다. 값 자체가 아니라 인덱스를 쓰는 이유는 간격이 불균등한 축에서 "한 칸"의 뜻이 흔들리기 때문이다(research R7,
 * FR-013a).
 */
@Service
public class GridExpander {

    /**
     * 전체 그리드. 반환 순서가 곧 후보 순번의 순서다.
     *
     * <p>축이 없는 전략은 빈 조합 1건이 된다 — 파라미터가 없는 전략도 계산 대상이다.
     */
    public List<GridPoint> expand(SearchSpace space) {
        List<GridPoint> points = new ArrayList<>();
        space.strategies()
                .forEach((strategyId, strategy) -> expandStrategy(strategyId, strategy, points));
        return List.copyOf(points);
    }

    private static void expandStrategy(
            String strategyId, SearchSpace.StrategySpace strategy, List<GridPoint> points) {
        List<SearchSpace.Axis> axes = strategy.axes();
        int[] indices = new int[axes.size()];
        while (true) {
            points.add(toPoint(strategyId, axes, indices));
            if (!advance(axes, indices)) {
                return;
            }
        }
    }

    private static GridPoint toPoint(
            String strategyId, List<SearchSpace.Axis> axes, int[] indices) {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Integer> coordinates = new LinkedHashMap<>();
        for (int i = 0; i < axes.size(); i++) {
            SearchSpace.Axis axis = axes.get(i);
            values.put(axis.name(), axis.values().get(indices[i]));
            coordinates.put(axis.name(), indices[i]);
        }
        return new GridPoint(
                strategyId, ParameterCombination.of(values), GridCoordinates.of(coordinates));
    }

    /** 마지막 축부터 올린다. 더 올릴 자리가 없으면 전개가 끝난 것이다. */
    private static boolean advance(List<SearchSpace.Axis> axes, int[] indices) {
        for (int i = axes.size() - 1; i >= 0; i--) {
            indices[i]++;
            if (indices[i] < axes.get(i).values().size()) {
                return true;
            }
            indices[i] = 0;
        }
        return false;
    }

    /**
     * 그리드의 한 점. 아직 유효성 판정을 거치지 않았으므로 후보가 아니다.
     *
     * @param strategyId 이 조합이 속한 전략
     * @param parameters 축 이름 → 값
     * @param coordinates 축 이름 → 값 목록에서의 인덱스
     */
    public record GridPoint(
            String strategyId, ParameterCombination parameters, GridCoordinates coordinates) {}
}
