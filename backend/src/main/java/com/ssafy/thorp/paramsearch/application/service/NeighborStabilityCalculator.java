package com.ssafy.thorp.paramsearch.application.service;

import com.ssafy.thorp.paramsearch.domain.model.CandidateStatus;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import com.ssafy.thorp.paramsearch.domain.vo.StabilityScore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 인접 설정 안정성 계산(FR-013a·FR-013b, research R7).
 *
 * <p>이웃은 <b>한 축의 그리드 인덱스만 ±1 다른 후보</b>다. 값 자체(관측 기간 60일 등)를 비교하지 않는 이유는 간격이 불균등한 축에서 "한 칸"의 뜻이 흔들리기
 * 때문이다 — 등록표가 준 후보값 순서의 인덱스가 그 뜻을 그대로 담는다(FR-029). 인덱스만 보므로 <b>축 값이 숫자가 아니어도 똑같이 판정된다.</b>
 *
 * <p><b>이웃은 같은 전략 안에서만 찾는다</b>(FR-029). 전략이 다르면 파라미터 축 자체가 다르므로 좌표를 나란히 놓을 수 없다 — 축 이름이 우연히 겹쳐도 뜻이
 * 다르다. {@link com.ssafy.thorp.paramsearch.domain.vo.GridCoordinates#isNeighborOf}가 축 집합까지 보지만,
 * 그것만으로는 축 구성이 같은 두 전략을 가르지 못한다.
 *
 * <p><b>완료 후보만 대상이자 이웃이다.</b> 실패·제외·미실행 후보는 비용 차감 후 수익이 없어 차를 잴 수 없다. 그 후보를 0으로 두고 계산에 넣으면 이웃이 크게
 * 어긋난 것처럼 보여 멀쩡한 후보가 떨어진다(FR-011, 원칙 VI).
 *
 * <p>이 클래스에 전략 지식이 없다 — 전략 ID를 같은지만 비교하고 그 뜻을 해석하지 않는다(FR-025).
 */
@Service
public class NeighborStabilityCalculator {

    /**
     * 평균을 낼 때 쓰는 소수 자릿수.
     *
     * <p>고정한 이유: 나눗셈 자릿수를 입력에 따라 바꾸면 같은 묶음을 다시 계산했을 때 값이 달라질 수 있고, 그러면 재현 기록이 흔들린다(원칙 VIII). 비용 차감
     * 후 수익이 소수 넷째 자리까지인 값이라 8자리면 평균에서 잘리는 자리가 없다.
     */
    static final int SCALE = 8;

    /**
     * 묶음 전체의 안정성을 한 번에 계산한다.
     *
     * <p>묶음 단위로 받는 이유: 이웃 판정은 다른 후보를 봐야 하고, 안정성 필터의 상위 50%가 <b>묶음 내 상대 분위</b>라 중간 상태에서 계산하면 값이
     * 흔들린다(내부 계약 §4 불변식 3).
     *
     * @param candidates 묶음의 후보 전체. 완료되지 않은 후보가 섞여 있어도 된다
     * @param netReturnByCandidateId 완료 후보의 비용 차감 후 수익. 값이 없는 후보는 완료로 보지 않는다
     * @return 후보 id → 안정성. 완료 후보만 담는다
     */
    public Map<Long, StabilityScore> calculate(
            List<SearchCandidate> candidates, Map<Long, BigDecimal> netReturnByCandidateId) {
        List<SearchCandidate> measurable = measurable(candidates, netReturnByCandidateId);
        Map<Long, StabilityScore> scores = new LinkedHashMap<>();
        for (SearchCandidate candidate : measurable) {
            scores.put(candidate.getId(), scoreOf(candidate, measurable, netReturnByCandidateId));
        }
        return scores;
    }

    /** 수익을 아는 완료 후보만 남긴다. 이웃 후보 집합이기도 하다. */
    private static List<SearchCandidate> measurable(
            List<SearchCandidate> candidates, Map<Long, BigDecimal> netReturnByCandidateId) {
        List<SearchCandidate> measurable = new ArrayList<>();
        for (SearchCandidate candidate : candidates) {
            if (candidate.getStatus() == CandidateStatus.COMPLETED
                    && candidate.getId() != null
                    && netReturnByCandidateId.get(candidate.getId()) != null) {
                measurable.add(candidate);
            }
        }
        return measurable;
    }

    private static StabilityScore scoreOf(
            SearchCandidate candidate,
            List<SearchCandidate> measurable,
            Map<Long, BigDecimal> netReturnByCandidateId) {
        BigDecimal own = netReturnByCandidateId.get(candidate.getId());
        BigDecimal sum = BigDecimal.ZERO;
        int neighbors = 0;
        for (SearchCandidate other : measurable) {
            if (!isNeighbor(candidate, other)) {
                continue;
            }
            sum = sum.add(own.subtract(netReturnByCandidateId.get(other.getId())).abs());
            neighbors++;
        }
        if (neighbors == 0) {
            // 경계 후보(축 끝)와 이웃이 모두 실패한 후보가 여기 온다. 0으로 채우지 않는다(FR-013b).
            return StabilityScore.uncalculable();
        }
        return StabilityScore.of(
                sum.divide(BigDecimal.valueOf(neighbors), SCALE, RoundingMode.HALF_UP), neighbors);
    }

    /** 같은 전략의 다른 후보이고 한 축만 한 칸 떨어져 있는가. */
    private static boolean isNeighbor(SearchCandidate candidate, SearchCandidate other) {
        return !other.getId().equals(candidate.getId())
                && other.getStrategyId().equals(candidate.getStrategyId())
                && candidate.getGridCoordinates().isNeighborOf(other.getGridCoordinates());
    }
}
