package com.ssafy.thorp.regimelink.domain.model;

import java.time.YearMonth;

/**
 * 선택한 ETF로 비교할 수 있는 사례 종료월의 범위 (FR-003, data-model §2).
 *
 * <p>비어 있을 수 있다 — 묶음의 종목 이력과 워밍업이 어떤 사례도 덮지 못하면 두 값이 모두 {@code null}이고 조회는 그대로 200이다(FR-019).
 */
public record AvailableRange(YearMonth firstCaseEndMonth, YearMonth lastCaseEndMonth) {

    private static final AvailableRange EMPTY = new AvailableRange(null, null);

    public AvailableRange {
        if (firstCaseEndMonth != null
                && lastCaseEndMonth != null
                && firstCaseEndMonth.isAfter(lastCaseEndMonth)) {
            throw new IllegalArgumentException(
                    "가용 범위의 시작이 끝보다 뒤입니다: " + firstCaseEndMonth + " > " + lastCaseEndMonth);
        }
        if ((firstCaseEndMonth == null) != (lastCaseEndMonth == null)) {
            throw new IllegalArgumentException("가용 범위는 양끝이 함께 있거나 함께 없어야 합니다.");
        }
    }

    public static AvailableRange empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return firstCaseEndMonth == null;
    }
}
