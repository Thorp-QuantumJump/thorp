package com.ssafy.thorp.regime.domain.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 사례 하나의 거리와 분야·블록 기여 (data-model §4, spec 010 v3 개정안 §5.3).
 *
 * <p>{@code contribution}이 {@code null}이면 <b>키 자체가 없다</b>는 뜻이다. 만들지 않는 자리는 둘 — 분야가 하나뿐일 때(값이 늘 1이라
 * "기여도 100%"라는 뜻 없는 숫자가 된다)와 전체 거리가 0일 때(0으로 나눈 값을 지어내지 않는다). 0을 채우면 "기여가 없다"로 읽히지만 실제로는 "말할 수
 * 없다"다.
 *
 * <p>v3 종합(모델 {@code REGIME-V3-EQ})은 분야 거리로 재지 않아 {@code byField}가 비고 {@code blocks}가 찬다. v1 결과는
 * {@code blocks}가 빈 목록이다.
 */
public record CaseDistance(
        BigDecimal value,
        Map<String, BigDecimal> byField,
        Map<String, BigDecimal> contribution,
        List<BlockGap> blocks,
        Map<String, BigDecimal> blockContribution,
        Boolean meetsThreshold) {

    /**
     * 교정된 유사성 기준을 만족했는가(spec FR-021). 기준이 교정되지 않은 검색 방식이면 {@code null}이다 — {@code false}로 채우면 "기준을
     * 못 넘었다"로 읽히지만 실제로는 판정하지 않았다.
     */
    public boolean meets() {
        return Boolean.TRUE.equals(meetsThreshold);
    }

    /** 경제 블록 하나의 거리. {@code spread}는 그 기준월 척도 구간의 블록 점수 표준편차다(수기 대조용). */
    public record BlockGap(String id, String name, BigDecimal gap, BigDecimal spread) {}
}
