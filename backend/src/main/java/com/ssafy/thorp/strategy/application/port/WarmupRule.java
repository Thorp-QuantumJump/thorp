package com.ssafy.thorp.strategy.application.port;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 전략 하나의 워밍업 산출 규칙(data-model §6).
 *
 * <p>{@code sum}의 항목을 모두 더한 값이 워밍업 거래일 수다. 문자열 항목은 축 이름이라 그 축의 <b>현재 값</b>으로 바꿔 읽고, 숫자 항목은 상수 그대로
 * 더한다.
 *
 * <p><b>상수만 더하는 형태로 두지 않은 이유</b>: 섹터 모멘텀의 '최근 구간 제외'는 사용자가 고르는 축이므로, 그 값이 바뀌면 워밍업도 따라 바뀌어야
 * 한다(research R4). 첫 설계({@code {basis, plus}})는 축 둘을 더하는 경우를 담지 못해 바꿨다.
 *
 * <p>누가 부르나: 화면이 설정을 바꿀 때마다 다시 계산해 카드를 판정하고(FR-011), 서버가 분석 요청을 받을 때 같은 규칙으로 다시 계산한다.
 *
 * @param sum 축 이름 또는 상수. 순서는 뜻이 없고 전부 더한다
 */
public record WarmupRule(List<Object> sum) {

    public WarmupRule {
        Objects.requireNonNull(sum, "warmup sum must not be null");
        if (sum.isEmpty()) {
            // 워밍업이 0이라는 뜻은 규칙을 적지 않는 것으로 나타낸다. 빈 배열은 둘 중 어느 쪽인지 말해 주지 않는다.
            throw new IllegalArgumentException("warmup sum must not be empty");
        }
        if (sum.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("warmup sum must not contain null");
        }
        sum = Collections.unmodifiableList(List.copyOf(sum));
    }

    /**
     * 이 설정값에서의 워밍업 거래일 수.
     *
     * <p><b>축 값이 없으면 예외다.</b> 없는 값을 0으로 채우면 워밍업이 조용히 짧아지고, 그 결과 평가 구간이 실제보다 길게 잡힌다 — 값을 지어내지 않는다.
     *
     * @param values 축 이름 → 현재 값
     */
    public int evaluate(Map<String, Object> values) {
        Objects.requireNonNull(values, "warmup values must not be null");
        BigDecimal total = BigDecimal.ZERO;
        for (Object term : sum) {
            total = total.add(term instanceof String axis ? axisValue(axis, values) : number(term));
        }
        return total.intValueExact();
    }

    private static BigDecimal axisValue(String axis, Map<String, Object> values) {
        Object value = values.get(axis);
        if (value == null) {
            throw new IllegalArgumentException("warmup needs a value for axis: " + axis);
        }
        return number(value);
    }

    private static BigDecimal number(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("warmup term is not a number: " + value, e);
        }
    }
}
