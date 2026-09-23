package com.ssafy.thorp.regimelink.application.repository;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Objects;

/**
 * 010이 정한 표시 후보 한 줄 (010 계약 §1).
 *
 * <p><b>011은 순위를 다시 매기지 않고 표시 후보 밖에서 뽑지도 않는다</b>(Clarifications 09-17 Q4). 여기 있는 값은 전부 010이 소유하며
 * 011은 판정만 붙인다.
 */
public record CaseCandidate(YearMonth endMonth, int rank, BigDecimal distance) {

    public CaseCandidate {
        Objects.requireNonNull(endMonth, "endMonth must not be null");
        if (rank < 1) {
            throw new IllegalArgumentException("순위는 1 이상이어야 합니다: " + rank);
        }
    }
}
