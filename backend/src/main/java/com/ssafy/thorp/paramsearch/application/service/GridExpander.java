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
     * 한 번에 펼칠 수 있는 조합 수의 기술적 한계.
     *
     * <p><b>후보 수 상한(FR-005b)이 아니다.</b> 그것은 등록표·한도 스냅샷이 정하는 값이고(현재 임시값 1,000) 이 숫자보다 훨씬 작다. 이 값은
     * "펼치다가 메모리를 다 쓰는 대신 사유를 남기고 멈춘다"는 방어선이며, 여기에 걸리는 요청은 한도 판정에서 이미 거부됐어야 하는 요청이다.
     *
     * <p>100만이 아니라 10만인 이유: 조합 하나가 축 개수만큼의 항을 가진 맵을 둘(값과 좌표) 들고 있다. 축이 스무 개면 100만 건은 항이 4천만 개여서 기본
     * 힙으로는 펼치는 중에 {@code OutOfMemoryError}가 난다 — 그러면 사유가 남지 않고 프로세스가 죽는다. 방어선은 죽기 전에 걸려야 한다.
     */
    static final long MAX_EXPANDABLE_POINTS = 100_000L;

    /**
     * 전체 그리드. 반환 순서가 곧 후보 순번의 순서다.
     *
     * <p>축이 없는 전략은 빈 조합 1건이 된다 — 파라미터가 없는 전략도 계산 대상이다.
     */
    public List<GridPoint> expand(SearchSpace space) {
        // 펼치기 전에 크기를 먼저 센다. 넘치는 공간을 그대로 펼치면 예외 없이 메모리를 다 쓸 때까지
        // 돌고, 그때는 원인이 요청 범위라는 것이 드러나지 않는다. 셈은 넘침을 예외로 알린다.
        long expected = space.combinationCount();
        if (expected > MAX_EXPANDABLE_POINTS) {
            throw new ArithmeticException(
                    "search space is too large to expand: combinations="
                            + expected
                            + ", max="
                            + MAX_EXPANDABLE_POINTS);
        }
        List<GridPoint> points = new ArrayList<>((int) expected);
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
