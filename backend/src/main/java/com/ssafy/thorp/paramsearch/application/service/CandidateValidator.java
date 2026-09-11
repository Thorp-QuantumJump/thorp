package com.ssafy.thorp.paramsearch.application.service;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.paramsearch.application.exception.RegistryUnavailableException;
import com.ssafy.thorp.paramsearch.application.port.ParameterRegistryPort;
import com.ssafy.thorp.paramsearch.application.port.SearchContext;
import com.ssafy.thorp.paramsearch.application.port.ValidationVerdict;
import com.ssafy.thorp.paramsearch.application.service.GridExpander.GridPoint;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 그리드의 각 점을 등록표의 유효성 규칙에 물어 계산 전에 거를 것을 거른다(FR-006).
 *
 * <p><b>위반 규칙을 007이 갖지 않는다.</b> 판정은 전부 {@link ParameterRegistryPort#validate}가 하고 이 클래스는 돌려받은
 * {@code reasonCode}·{@code reasonDetail}을 그대로 옮긴다 — 위반 코드 목록을 상수로 두거나 전략별로 분기하지 않는다(FR-025). 어떤
 * 제약이 있는지는 003이 소유한다.
 *
 * <p>제외된 조합은 계산하지 않는다. 실행 자원을 아끼는 것보다 중요한 이유는, 제약 위반을 실행 실패로 남기면 "제약 위반"과 "낮은 수익"의 구분이 흐려지기 때문이다(원칙
 * VI).
 */
@Service
public class CandidateValidator {

    private final ParameterRegistryPort registry;

    public CandidateValidator(ParameterRegistryPort registry) {
        this.registry = registry;
    }

    /** 그리드 전체를 판정한다. 입력 순서를 그대로 보존한다 — 그 순서가 후보 순번이다(FR-029). */
    public List<ValidatedPoint> validate(List<GridPoint> points, SearchContext context) {
        List<ValidatedPoint> validated = new ArrayList<>(points.size());
        for (GridPoint point : points) {
            validated.add(new ValidatedPoint(point, verdictOf(point, context)));
        }
        return List.copyOf(validated);
    }

    private ValidationVerdict verdictOf(GridPoint point, SearchContext context) {
        try {
            return registry.validate(point.strategyId(), point.parameters(), context);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            // 판정을 받지 못한 조합을 유효로 넘기면 계산할 수 없는 것을 실행하게 된다(FR-028).
            throw new RegistryUnavailableException("003 유효성 규칙: " + point.strategyId(), e);
        }
    }

    /** 판정을 마친 그리드 점. 사유는 등록표가 준 값 그대로다. */
    public record ValidatedPoint(GridPoint point, ValidationVerdict verdict) {

        public boolean isValid() {
            return verdict.valid();
        }
    }
}
