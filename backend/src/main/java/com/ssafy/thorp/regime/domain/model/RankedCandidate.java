package com.ssafy.thorp.regime.domain.model;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * 전체 유효 후보 목록의 한 줄 (data-model §2.3).
 *
 * <p><b>±6개월 건너뛰기를 적용하지 않은 원본 순위다.</b> 건너뛰기는 하나씩 담을 때마다 적용되는 순차 규칙이라, 걸러 둔 목록에 011이 가용성 필터를 얹으면 어느
 * 후보가 걸러졌어야 하는지 다시 계산할 수 없다(spec FR-012·FR-030).
 */
public record RankedCandidate(YearMonth endMonth, BigDecimal distance, int rank) {}
