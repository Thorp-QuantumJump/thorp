package com.ssafy.thorp.regime.domain.model;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/**
 * 사례 하나에서 지표 하나가 낸 값 (data-model §4의 {@code indicator_values}).
 *
 * <p>{@code queryRaw}·{@code caseRaw}는 <b>표준화 전 값</b>이라는 뜻이고, 배치가 {@code monthly_series()}에서 읽은
 * <b>변환 후 값</b>이다(spec FR-015). 증가율 지표면 {@code +3.2%}이지 지수 원수준이 아니다.
 *
 * <p>비교 시점이 여럿이라 값도 시점 수만큼이다 — 하나로 줄이면 SC-003의 수기 대조에서 {@code E_i}를 다시 만들 수 없다.
 *
 * <p>{@code nominalWeight}(명목 비중 {@code 1/n})와 {@code distanceShare}(실제 거리 기여 {@code E_i ÷ 분야 안
 * ΣE_i})를 나란히 둔다 — 명목 비중이 같아도 실제 기여는 다를 수 있고, 그 차이가 U02 검증의 관측 대상이다(spec FR-010). {@code
 * distanceShare}가 {@code null}이면 분야 안 {@code ΣE_i}가 0이라 <b>키를 생략</b>한다.
 */
public record CaseIndicatorValue(
        String indicatorId,
        String unit,
        List<YearMonth> comparisonMonths,
        List<YearMonth> caseMonths,
        List<BigDecimal> queryRaw,
        List<BigDecimal> caseRaw,
        List<BigDecimal> queryZ,
        List<BigDecimal> caseZ,
        BigDecimal e,
        BigDecimal nominalWeight,
        BigDecimal distanceShare) {}
