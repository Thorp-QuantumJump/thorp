package com.ssafy.thorp.regime.domain.model;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 사례 하나의 거리와 분야 기여 (data-model §4).
 *
 * <p>{@code contribution}이 {@code null}이면 <b>키 자체가 없다</b>는 뜻이다. 만들지 않는 자리는 둘 — 분야가 하나뿐일 때(값이 늘 1이라
 * "기여도 100%"라는 뜻 없는 숫자가 된다)와 전체 거리가 0일 때(0으로 나눈 값을 지어내지 않는다). 0을 채우면 "기여가 없다"로 읽히지만 실제로는 "말할 수
 * 없다"다.
 */
public record CaseDistance(
        BigDecimal value, Map<String, BigDecimal> byField, Map<String, BigDecimal> contribution) {}
