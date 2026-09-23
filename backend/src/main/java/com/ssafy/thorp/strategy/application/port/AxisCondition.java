package com.ssafy.thorp.strategy.application.port;

import java.util.Map;
import java.util.Objects;

/**
 * 축이 뜻을 갖는 조건(spec FR-003, data-model §4-1).
 *
 * <p>섹터 모멘텀의 선정 수·임계값·상위 비율은 고른 선정 방식에 해당하는 하나만 뜻이 있다. <b>그 대응을 화면이나 서비스 코드에 적으면 축 이름으로 분기하는 것이고,
 * 그것이 FR-005가 금지한 전략 지식의 중복이다.</b> 그래서 등록표가 "이 축은 저 축이 이 값일 때만 쓴다"를 적고, 화면·접수 관문·조합 생성은 이 조건을 기계적으로
 * 평가할 뿐 축의 뜻을 읽지 않는다.
 *
 * <p>조건이 없는 축은 늘 쓴다. 조건이 거짓인 축은 화면이 그리지 않고, 요청에 실리지 않으며, 접수 관문도 그 축이 빠진 것을 결함으로 보지 않는다.
 *
 * @param axis 조건을 결정하는 축 이름. 같은 전략의 다른 축이어야 한다
 * @param value 그 축이 이 값일 때 이 축을 쓴다. 비교는 {@code equals}뿐이다({@code ParameterAxis.indexOf}와 같은 규약)
 */
public record AxisCondition(String axis, Object value) {

    public AxisCondition {
        if (axis == null || axis.isBlank()) {
            throw new IllegalArgumentException("appliesWhen.axis must not be blank");
        }
        Objects.requireNonNull(value, "appliesWhen.value must not be null");
    }

    /** 지금 설정에서 이 조건이 참인가. 결정 축의 값이 없으면 거짓이다 — 없는 값으로 축을 열지 않는다. */
    public boolean isSatisfiedBy(Map<String, Object> settings) {
        return Objects.equals(settings.get(axis), value);
    }
}
