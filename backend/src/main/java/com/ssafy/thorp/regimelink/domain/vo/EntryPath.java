package com.ssafy.thorp.regimelink.domain.vo;

/**
 * 사용자가 어느 화면에서 들어왔나 (data-model §3, FR-004).
 *
 * <p>두 경로의 결과를 따로 보존하며, 가용성 필터를 거는지가 여기서 갈린다.
 */
public enum EntryPath {

    /** 시장 흐름 → 사례에서 시작. 필터를 걸지 않고 판정만 붙인다(FR-002). */
    MARKET_FLOW,

    /** 진단 결과 → 비교 묶음으로 시작. 공통 가용 범위 필터를 건다(FR-003). */
    STRATEGY_RESULT;

    public boolean filtered() {
        return this == STRATEGY_RESULT;
    }

    public static EntryPath from(String value) {
        if (value == null) {
            throw new IllegalArgumentException("entryPath 가 없습니다.");
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("알 수 없는 entryPath 입니다: " + value, e);
        }
    }
}
