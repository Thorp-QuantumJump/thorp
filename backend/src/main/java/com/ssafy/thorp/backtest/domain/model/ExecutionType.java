package com.ssafy.thorp.backtest.domain.model;

/**
 * 실행을 만들어 낸 사용 맥락(spec 004 FR-007). 실행 측정을 이 단위로 나눠 대기·소요·실패율을 본다 — 단일 실행과 자동 탐색 묶음은 한 건의 무게가 달라 섞어
 * 집계하면 둘 다 의미를 잃는다.
 *
 * <p>값을 정하는 쪽은 요청자다. 004는 기본값 {@link #SINGLE}만 쓰고, 나머지는 007·010이 접수할 때 지정한다.
 */
public enum ExecutionType {
    /** 전략 하나를 직접 실행(003 '분석하기'). */
    SINGLE,
    /** 자동 파라미터 탐색 묶음의 후보 또는 기준(007). */
    SEARCH_BUNDLE,
    /** 경제환경 분야별 검색에서 파생된 실행(010). */
    REGIME_SECTOR,
    /** 경제환경 종합 비교에서 파생된 실행(010). */
    REGIME_TOTAL
}
