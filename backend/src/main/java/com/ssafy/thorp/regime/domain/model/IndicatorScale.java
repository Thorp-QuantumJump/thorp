package com.ssafy.thorp.regime.domain.model;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * 지표 하나의 <b>공통 척도</b> (data-model §2).
 *
 * <p>질의와 모든 후보가 이 하나를 쓴다 — 같은 조건 비교(원칙 IV)가 값의 모양으로 고정된 자리다. 후보월로 만든 척도는 여기 들어올 자리가 없고, 그쪽은 {@link
 * EraPosition}의 보조 설명에만 쓰인다(FR-017).
 *
 * <p>{@code windowStart}·{@code windowEnd}·{@code months}는 <b>실제로 쓴</b> 구간이다. 구멍 때문에 짧아진 구간으로 계산했다는
 * 사실이 남아야 나중에 값을 대조할 수 있다(spec FR-028, SC-003).
 */
public record IndicatorScale(
        String indicatorId,
        BigDecimal median,
        BigDecimal spread,
        String method,
        YearMonth windowStart,
        YearMonth windowEnd,
        int months) {}
