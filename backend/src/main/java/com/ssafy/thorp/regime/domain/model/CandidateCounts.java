package com.ssafy.thorp.regime.domain.model;

import java.util.Map;

/**
 * 후보 집계 (data-model §2).
 *
 * <p>{@code NEIGHBOR_SKIPPED}는 <b>전체 유효 후보 목록에서 빠지지 않는다</b> — 건너뛰기는 표시할 사례를 고를 때만 적용한다. 그래서 이 수는
 * {@code evaluated}에서 빼는 값이 아니라 "표시에서 접힌 수"다.
 *
 * <p>화면에 나가는 것은 {@code evaluated}·{@code eligible} 자체가 아니라 상한에 걸렸다는 <b>사실</b>뿐이다(spec FR-013,
 * SC-004) — 수가 놓이면 "그만큼 비슷했다"로 읽힌다.
 */
public record CandidateCounts(int evaluated, Map<String, Integer> excluded) {}
