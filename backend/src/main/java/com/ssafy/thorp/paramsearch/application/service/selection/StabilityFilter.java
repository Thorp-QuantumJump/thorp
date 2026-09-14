package com.ssafy.thorp.paramsearch.application.service.selection;

import com.ssafy.thorp.paramsearch.domain.model.SelectionRule;
import com.ssafy.thorp.paramsearch.domain.vo.StabilityScore;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 인접 설정 안정성 필터(FR-013·FR-013b).
 *
 * <p><b>묶음 안의 상대 분위다</b> — "안정성 상위 {@code keepTopPercent}%". 절대 문턱을 쓰지 않는 이유는 안정성 값의 크기가 전략·구간·자료에
 * 따라 통째로 달라져서, 한 번 정한 숫자가 다른 묶음에서는 전부 통과나 전부 탈락이 되기 때문이다(FR-013).
 *
 * <p><b>값이 작을수록 안정적이다.</b> 이웃과 결과 차가 작다는 뜻이라, 우연히 좋았던 한 점을 고르는 것을 막는다.
 *
 * <p><b>계산 불가는 통과시킨다</b>(FR-013b). 이웃이 없는 것은 후보의 흠이 아니라 그리드 경계의 성질이다. 떨어뜨리면 축 끝 값이 구조적으로 선정에서 빠지고,
 * 0으로 채워 통과시키면 "가장 안정적"이 되어 정반대로 유리해진다 — 둘 다 사실이 아니다.
 *
 * <p>상대 분위라서 <b>묶음이 끝난 뒤 한 번만</b> 계산해야 한다. 중간 상태에서 재면 나중에 끝난 후보가 분위를 흔든다(내부 계약 §4 불변식 3).
 */
@Component
public class StabilityFilter {

    /**
     * 통과 문턱을 구한다. 계산 가능한 후보들의 안정성만 보고 상위 {@code keepTopPercent}% 경계값을 돌려준다.
     *
     * <p><b>경계값과 같은 후보는 전부 통과시킨다.</b> 동점을 임의로 자르면 같은 값을 가진 두 후보의 운명이 정렬 순서로 갈리는데, 그 순서에는 근거가
     * 없다(R3). 그래서 "상위 N개"가 아니라 "경계값 이하 전부"로 자른다 — 결과가 정렬 구현에 의존하지 않는다.
     *
     * <p>올림을 쓰는 이유: 계산 가능한 후보가 1개일 때 내림이면 0개가 남아 필터가 전부를 떨어뜨린다. 분위 필터가 후보를 전멸시키는 것은 "상위 절반"의 뜻이
     * 아니다.
     *
     * @return 경계값. 계산 가능한 후보가 없으면 null이고 그때는 모두 통과다
     */
    public BigDecimal thresholdOf(SelectionRule rule, Collection<StabilityScore> scores) {
        List<BigDecimal> calculable = new ArrayList<>();
        for (StabilityScore score : scores) {
            if (score != null && score.calculable()) {
                calculable.add(score.value());
            }
        }
        if (calculable.isEmpty()) {
            return null;
        }
        calculable.sort(BigDecimal::compareTo);
        int keep =
                Math.max(
                        1,
                        (int)
                                Math.ceil(
                                        calculable.size()
                                                * rule.stabilityFilter().keepTopPercent()
                                                / 100.0));
        return calculable.get(Math.min(keep, calculable.size()) - 1);
    }

    /**
     * 후보가 안정성 문턱을 통과하는가.
     *
     * @param threshold {@link #thresholdOf}가 구한 경계값. null이면 잴 대상이 없어 모두 통과다
     */
    public boolean passes(StabilityScore score, BigDecimal threshold) {
        if (threshold == null || score == null || !score.calculable()) {
            return true;
        }
        return score.value().compareTo(threshold) <= 0;
    }
}
