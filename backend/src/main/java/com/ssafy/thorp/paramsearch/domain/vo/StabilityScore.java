package com.ssafy.thorp.paramsearch.domain.vo;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 인접 설정 안정성(FR-013a·FR-013b).
 *
 * <p>"이 후보의 설정을 한 칸 옮겼을 때 결과가 얼마나 달라지는가"를 이웃과의 비용 차감 후 수익 차 절댓값 평균으로 나타낸다. <b>값이 작을수록 안정적이다</b> —
 * 주변 설정과 결과가 비슷하다는 뜻이라, 우연히 좋았던 한 점을 고르는 것을 막는다.
 *
 * <p><b>이웃이 없으면 0이 아니라 "계산 불가"다</b>(FR-013b, 원칙 VI). 0으로 채우면 "차이가 전혀 없다 = 매우 안정적"으로 읽혀 계산 불가와 정반대
 * 뜻이 된다. 그래서 {@code calculable = false}일 때 {@code value}는 반드시 {@code null}이고, 안정성 필터는 이런 후보를 떨어뜨리지
 * 않고 통과시킨다.
 *
 * <p>도메인 계층이라 Jackson을 쓰지 않는다({@code ArchitectureTest} 규칙). {@link #toMap()}으로 순수 {@code Map}만 내주고
 * JSONB 직렬화는 infrastructure가 맡는다 — {@link SearchSpace}·{@code SelectionRule}과 같은 방식이다.
 */
public record StabilityScore(boolean calculable, BigDecimal value, int neighborCount) {

    public StabilityScore {
        if (neighborCount < 0) {
            throw new IllegalArgumentException(
                    "neighborCount must not be negative: " + neighborCount);
        }
        if (calculable && value == null) {
            throw new IllegalArgumentException("calculable stability must carry a value");
        }
        if (!calculable && value != null) {
            // 계산 불가인데 값이 있으면 화면이 그 값을 안정성으로 읽는다. 둘 중 하나는 거짓이다.
            throw new IllegalArgumentException("uncalculable stability must not carry a value");
        }
    }

    /** 이웃이 있어 계산된 안정성. */
    public static StabilityScore of(BigDecimal value, int neighborCount) {
        if (neighborCount < 1) {
            throw new IllegalArgumentException(
                    "calculable stability needs at least one neighbor: " + neighborCount);
        }
        return new StabilityScore(true, value, neighborCount);
    }

    /** 이웃이 없어 계산할 수 없다. 필터는 이 후보를 통과시킨다(FR-013b). */
    public static StabilityScore uncalculable() {
        return new StabilityScore(false, null, 0);
    }

    /**
     * 저장·전송용 {@code Map}. 키 순서는 contracts/param-search-api.md §4의 예시와 같다.
     *
     * <p><b>값은 문자열이다.</b> JSON 숫자로 두면 읽을 때 배정밀도로 돌아와 자릿수를 잃는다(계약의 금액·비율 규약).
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("calculable", calculable);
        map.put("value", value == null ? null : value.toPlainString());
        map.put("neighborCount", neighborCount);
        return map;
    }

    /** 저장된 {@code Map}에서 복원한다. 키가 없으면 계산 불가로 본다. */
    public static StabilityScore fromMap(Map<String, Object> map) {
        if (map == null || !Boolean.TRUE.equals(map.get("calculable"))) {
            return uncalculable();
        }
        Object raw = map.get("value");
        if (raw == null) {
            return uncalculable();
        }
        return of(new BigDecimal(raw.toString()), toInt(map.get("neighborCount")));
    }

    private static int toInt(Object raw) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        return raw == null ? 0 : Integer.parseInt(raw.toString());
    }
}
