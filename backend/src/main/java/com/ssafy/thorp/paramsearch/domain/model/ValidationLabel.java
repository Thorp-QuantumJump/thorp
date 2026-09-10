package com.ssafy.thorp.paramsearch.domain.model;

/**
 * 후보 결과가 어느 구간에서 나온 값인지 나타내는 라벨(FR-014, 원칙 VII).
 *
 * <p>평가 분할(PD-12)이 미확정이라 <b>현재 모든 후보가 {@link #UNVALIDATED}</b>다(research R5). 전 후보가 미검증이므로 SC-005는
 * 자동으로 만족하며, 이를 검증 통과로 보고하지 않는다.
 *
 * <ul>
 *   <li>{@link #SEARCH_RESULT} — 탐색에 쓴 구간의 성과. 탐색 성과이며 검증된 성과가 아니다
 *   <li>{@link #HELD_OUT_RESULT} — 탐색에 쓰지 않은 구간의 성과
 *   <li>{@link #UNVALIDATED} — 구간이 나뉘지 않아 검증 여부를 말할 수 없다
 * </ul>
 */
public enum ValidationLabel {
    SEARCH_RESULT,
    HELD_OUT_RESULT,
    UNVALIDATED;

    /** 검증된 성과로 표시할 수 있는가. 탐색 구간 성과와 미검증은 둘 다 아니다. */
    public boolean isValidated() {
        return this == HELD_OUT_RESULT;
    }
}
