package com.ssafy.thorp.sector.domain.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 대표 상품 정보 (FR-011·FR-015). 사용자가 결과의 근거와 한계를 확인하는 묶음이다.
 *
 * <p>기준 지수·상장일·운용사는 여기 없다. 그 셋은 상품 자체의 값이라 {@link RepresentativeEtf}가 갖고, 저장 스키마도 {@code
 * representative_etf}에 둔다(data-model.md). 이 모델은 "자료 버전마다 새로 만드는 설명 값"만 담는다.
 *
 * <p><b>추적 차이 필드를 두지 않는다.</b> 계산 정의가 여러 갈래(지수 대비인지 순자산가치 대비인지, 분배금 재투자를 넣는지, 어느 기간인지)라 확정 전에 표시하면
 * 검증할 수 없는 숫자를 보여주게 된다. 결정 항목 PD-21이며 이번 범위에서 뺐다(spec.md Out of Scope). 확정되면 필드를 더하는 방향으로 넓힌다.
 *
 * @param asOfCurrentComposition true면 화면은 "당시 구성을 재현했다"고 표현하지 않는다(FR-015). 과거 구성 이력 복원은 범위 밖이라 지금
 *     만드는 값은 모두 현재 구성 기준이다
 * @param avgDailyValue3m 최근 3개월 일평균 거래대금(원). 근거는 {@code daily_price.value}(거래대금)이며 거래량에 종가를 곱한 추정이
 *     아니다(research.md 결정 6)
 */
public record InstrumentProfile(
        String indexMethodologySummary,
        List<Holding> topHoldings,
        boolean asOfCurrentComposition,
        BigDecimal avgDailyValue3m,
        String sourceAttribution,
        String survivorshipNote) {

    public InstrumentProfile {
        requireText(indexMethodologySummary, "indexMethodologySummary");
        Objects.requireNonNull(topHoldings, "topHoldings must not be null");
        topHoldings = List.copyOf(topHoldings);
        if (topHoldings.isEmpty()) {
            // 빈 목록은 "중복이 없다"로 읽힌다. 자료가 없으면 행 자체를 만들지 않는 것이 원칙 VI다.
            throw new IllegalArgumentException("topHoldings must not be empty");
        }
        BigDecimal total =
                topHoldings.stream().map(Holding::weight).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(
                    "sum of holding weights must not exceed 1: " + total);
        }
        Objects.requireNonNull(avgDailyValue3m, "avgDailyValue3m must not be null");
        if (avgDailyValue3m.signum() < 0) {
            throw new IllegalArgumentException("avgDailyValue3m must not be negative");
        }
        requireText(sourceAttribution, "sourceAttribution");
        requireText(survivorshipNote, "survivorshipNote");
    }

    /**
     * 구성종목 한 건. 비중은 1을 100%로 보는 비율이다.
     *
     * <p>중첩 record로 두는 이유는 이 값이 {@link InstrumentProfile} 밖에서 홀로 쓰이지 않기 때문이다.
     */
    public record Holding(String name, BigDecimal weight) {

        public Holding {
            requireText(name, "holding name");
            Objects.requireNonNull(weight, "weight must not be null");
            if (weight.signum() <= 0 || weight.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("weight must be in (0, 1]: " + weight);
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank: " + value);
        }
    }
}
