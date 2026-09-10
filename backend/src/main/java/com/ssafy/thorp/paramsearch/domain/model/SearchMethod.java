package com.ssafy.thorp.paramsearch.domain.model;

/**
 * 탐색 방법. 재현 기록(FR-020)의 일부다.
 *
 * <p>현재 {@link #FULL_GRID} 하나뿐이다(FR-005). 상한을 넘는 탐색 공간은 <b>표본 추출이나 간격 확대로 줄이지 않고 접수를
 * 거부한다</b>(FR-005b) — 사용자가 고른 조합과 다른 것을 계산해 주면 결과가 무엇의 성과인지 흐려진다.
 *
 * <p>무작위 표본을 도입하면 값이 늘고 그때 {@code random_seed}가 채워진다. 지금 그 컬럼이 항상 NULL인 이유다.
 */
public enum SearchMethod {
    FULL_GRID
}
