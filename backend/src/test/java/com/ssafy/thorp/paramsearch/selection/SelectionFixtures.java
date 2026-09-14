package com.ssafy.thorp.paramsearch.selection;

import com.ssafy.thorp.paramsearch.application.port.CandidatePerformance;
import com.ssafy.thorp.paramsearch.domain.model.CandidateStatus;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import com.ssafy.thorp.paramsearch.domain.model.SelectionRule;
import com.ssafy.thorp.paramsearch.domain.model.ValidationLabel;
import com.ssafy.thorp.paramsearch.domain.vo.GridCoordinates;
import com.ssafy.thorp.paramsearch.domain.vo.ParameterCombination;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 선정 테스트가 함께 쓰는 고정 입력.
 *
 * <p><b>전략 ID를 지어낸다.</b> 운영 등록표의 전략을 쓰지 않는 이유는 등록표가 바뀌어도 선정 규칙은 그대로여야 하기 때문이고, 선정 코드가 전략을 알지 못한다는 것
 * 자체가 검증 대상이기 때문이다(FR-025).
 */
final class SelectionFixtures {

    /** 이 테스트들이 쓰는 전략. 운영 등록표에 없는 이름이다. */
    static final String STRATEGY = "TEST_STRATEGY";

    private SelectionFixtures() {}

    /** 기준 전략 역할이 지정된 묶음의 규칙. 접수 시점 스냅샷과 같은 모양이다. */
    static SelectionRule ruleWithBaseline() {
        return rule(new SelectionRule.DrawdownFilter("NOT_WORSE_THAN_BASELINE", "SOME_BASELINE"));
    }

    /** 등록표가 기준 전략 역할을 지정하지 않은 묶음의 규칙(FR-013d). */
    static SelectionRule ruleWithoutBaseline() {
        return rule(new SelectionRule.DrawdownFilter("NOT_APPLIED", "NO_BASELINE_REGISTERED"));
    }

    private static SelectionRule rule(SelectionRule.DrawdownFilter drawdownFilter) {
        return new SelectionRule(
                drawdownFilter,
                new SelectionRule.StabilityFilter("BATCH_PERCENTILE", 50),
                "NET_RETURN",
                5,
                "SHOW_ALL_TIED",
                new SelectionRule.StabilityFormula(
                        "SINGLE_AXIS_ONE_STEP", "MEAN_ABS_NET_RETURN_DIFF"),
                List.of("displayCount"));
    }

    static SearchCandidate completed(long id) {
        return candidate(id, CandidateStatus.COMPLETED, null, null);
    }

    static SearchCandidate excluded(long id, String code, String detail) {
        return candidate(id, CandidateStatus.EXCLUDED, code, detail);
    }

    static SearchCandidate failed(long id, String code, String detail) {
        return candidate(id, CandidateStatus.FAILED, code, detail);
    }

    private static SearchCandidate candidate(
            long id, CandidateStatus status, String reasonCode, String reasonDetail) {
        return SearchCandidate.restore()
                .id(id)
                .paramSearchId(1L)
                .candidateNo((int) id)
                .strategyId(STRATEGY)
                .parameters(ParameterCombination.of(Map.of("x", id)))
                .gridCoordinates(GridCoordinates.of(Map.of("x", (int) id)))
                .status(status)
                .reasonCode(reasonCode)
                .reasonDetail(reasonDetail)
                .validationLabel(ValidationLabel.UNVALIDATED)
                .build();
    }

    static CandidatePerformance performance(String netReturn, String maxDrawdown) {
        return new CandidatePerformance(
                new BigDecimal(netReturn), new BigDecimal(maxDrawdown), new BigDecimal("0.0100"));
    }
}
