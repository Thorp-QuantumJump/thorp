package com.ssafy.thorp.strategy.domain.model;

import com.ssafy.thorp.strategy.domain.vo.ConstraintOperator;
import java.util.Objects;

/**
 * 두 파라미터 사이에 성립해야 하는 관계(FR-009).
 *
 * <p>개별 파라미터의 유효 범위와 <b>분리된 목록</b>으로 존재한다(FR-009b). "단기 기간은 2 이상 120 이하"는 각 파라미터의 {@link
 * com.ssafy.thorp.strategy.domain.vo.NumericRange}가 담고, "단기 기간은 장기 기간보다 짧다"는 여기가 담는다. 한 표현 수단에 둘을
 * 모두 밀어 넣으면 어떤 제약이 깨졌는지 사용자에게 특정해 알려줄 수 없다.
 *
 * <p>양변은 모두 파라미터 이름이며 상수를 쓸 수 없다. 상수와의 비교는 유효 범위가 담당하는 몫이라, 두 곳에 같은 규칙을 쓸 수 있게 두면 정의 작성자마다 다른 곳에 적게
 * 되고 입력 화면이 어느 쪽을 근거로 힌트를 만들지 모호해진다.
 *
 * <p>참조하는 이름이 실제로 정의에 있는지(D5), 그 파라미터가 항상 값을 갖는지(D6), 연산자에 맞는 타입인지(D7·D8)는 형식 검증이 다룬다.
 *
 * @param left 좌변 파라미터 이름
 * @param operator 성립해야 하는 관계
 * @param right 우변 파라미터 이름
 * @param message 위반 시 사용자에게 보일 설명
 */
public record ParameterConstraint(
        String left, ConstraintOperator operator, String right, String message) {

    public ParameterConstraint {
        Objects.requireNonNull(left, "제약의 좌변은 null일 수 없다");
        Objects.requireNonNull(right, "제약의 우변은 null일 수 없다");
        Objects.requireNonNull(operator, "제약의 연산자는 null일 수 없다");

        requireParameterName(left, "좌변");
        requireParameterName(right, "우변");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException(
                    "제약의 설명은 비어 있을 수 없다: %s %s %s".formatted(left, operator, right));
        }
    }

    private static void requireParameterName(String value, String side) {
        if (!ParameterDefinition.NAME_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "제약의 %s은 파라미터 이름이어야 한다(상수 불가): %s".formatted(side, value));
        }
    }

    /** 이 제약이 참조하는 파라미터 이름을 담고 있는지. */
    public boolean references(String parameterName) {
        return left.equals(parameterName) || right.equals(parameterName);
    }
}
