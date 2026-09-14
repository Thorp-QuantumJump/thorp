package com.ssafy.thorp.paramsearch.application.port;

/**
 * 후보·기준이 가리키는 실행을 목록에서 보여 주는 데 필요한 값.
 *
 * <p>{@code backtestId}는 <b>요청</b>의 식별자다 — 사용자가 {@code GET /backtests/{id}}로 결과를 여는 값이 그것이다(004
 * R-02). 실행 행도 자기 {@code backtest_id}를 갖지만 그 값으로는 상세가 열리지 않는다.
 *
 * @param performance 결과 행이 없으면 null. 실행 중이거나 실패한 실행이며 0으로 채우지 않는다(FR-011)
 */
public record CandidateRunView(
        String backtestId, String status, CandidatePerformance performance) {}
