package com.ssafy.thorp.strategy.domain.model;

import com.ssafy.thorp.strategy.domain.vo.ResolvedParameters;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 파라미터 값 집합 하나에 대한 판정 결과.
 *
 * <p>수용과 거부가 하나의 타입으로 표현된다. 거부일 때 {@code resolvedParameters}와 {@code requiredHistoryBars}가 {@code
 * null}인 것은 실수가 아니라 규칙이다 — 값이 유효하지 않은데 확정 값 집합을 내놓으면 그것을 그대로 실행에 쓰는 소비 지점이 생긴다.
 *
 * @param valid 위반이 하나도 없으면 {@code true}
 * @param violations 발견된 모든 위반. 첫 위반에서 멈추지 않는다(FR-012)
 * @param resolvedParameters 기본값이 채워진 확정 값 집합. 거부면 {@code null}
 * @param requiredHistoryBars 확정 값 기준 최소 이력 요구량. 거부면 {@code null}
 */
public record ValidationOutcome(
        boolean valid,
        List<ParameterViolation> violations,
        ResolvedParameters resolvedParameters,
        Integer requiredHistoryBars) {

    public ValidationOutcome {
        Objects.requireNonNull(violations, "위반 목록은 null일 수 없다(비어 있을 수는 있다)");
        violations = List.copyOf(violations);

        if (valid != violations.isEmpty()) {
            throw new IllegalArgumentException(
                    "판정 결과와 위반 목록이 어긋난다: valid=%s, violations=%d"
                            .formatted(valid, violations.size()));
        }
        if (valid && (resolvedParameters == null || requiredHistoryBars == null)) {
            throw new IllegalArgumentException("수용된 판정은 확정 값 집합과 이력 요구량을 함께 내놓아야 한다");
        }
        if (!valid && (resolvedParameters != null || requiredHistoryBars != null)) {
            throw new IllegalArgumentException("거부된 판정은 확정 값 집합이나 이력 요구량을 가질 수 없다");
        }
        if (requiredHistoryBars != null && requiredHistoryBars < 0) {
            throw new IllegalArgumentException("이력 요구량은 0 이상이어야 한다: " + requiredHistoryBars);
        }
    }

    public static ValidationOutcome accepted(
            ResolvedParameters resolvedParameters, int requiredHistoryBars) {
        return new ValidationOutcome(true, List.of(), resolvedParameters, requiredHistoryBars);
    }

    public static ValidationOutcome rejected(List<ParameterViolation> violations) {
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("거부하려면 위반이 하나 이상이어야 한다");
        }
        return new ValidationOutcome(false, violations, null, null);
    }

    public static ValidationOutcome rejected(ParameterViolation violation) {
        return rejected(List.of(violation));
    }

    public Optional<ResolvedParameters> resolved() {
        return Optional.ofNullable(resolvedParameters);
    }

    public Optional<Integer> historyBars() {
        return Optional.ofNullable(requiredHistoryBars);
    }
}
