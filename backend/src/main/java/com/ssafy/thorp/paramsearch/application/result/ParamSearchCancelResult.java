package com.ssafy.thorp.paramsearch.application.result;

/**
 * 취소 접수 결과(contracts/param-search-api.md §5).
 *
 * @param status 응답 시점의 상태. <b>아직 {@code RUNNING}일 수 있다</b> — 이미 큐에 들어간 후보는 회수할 수 없어 완주하고, 그것이 다 끝나야
 *     {@code PARTIAL}이 된다(research R2)
 * @param skipped 발행 전이라 '미실행'으로 돌린 후보 수
 * @param inFlight 이미 발행돼 완주할 후보 수. 이 값이 0이면 응답 시점에 이미 {@code PARTIAL}이다
 */
public record ParamSearchCancelResult(String searchId, String status, int skipped, long inFlight) {}
