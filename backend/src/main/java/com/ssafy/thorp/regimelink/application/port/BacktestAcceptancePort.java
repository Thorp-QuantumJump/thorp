package com.ssafy.thorp.regimelink.application.port;

import java.time.LocalDate;

/**
 * 011이 사용자 이름으로 004에 파생 실행을 접수한다 (contracts §2, T058·T067·T069).
 *
 * <p><b>004 코드에 011 전용 경로를 만들지 않는다.</b> 접수는 004 {@code BacktestService}가 이미 가진 것을 그대로 쓰고, 여기서는 "기준
 * 실행의 사본에 기간·시나리오만 바꾼 요청"을 만드는 규칙만 정한다.
 *
 * <p>접수는 <b>요청한 사용자의 요청</b>이다. 실행이 재사용돼도 004는 호출자마다 새 요청과 새 공개 토큰을 만들므로, 두 사용자가 같은 사례를 켜도 실행은 하나이고
 * 분석 요청은 각자 하나다.
 */
public interface BacktestAcceptancePort {

    /**
     * 기준 실행의 조건 사본으로 기간만 넓힌 실행을 접수한다 (전체 기간 재계산).
     *
     * @param originRunId 조건을 베낄 실행
     * @param start 공통 가용 범위의 시작. {@code end}까지가 요청 기간이다
     */
    AcceptedRun acceptFullPeriod(
            Long originRunId, long requesterId, LocalDate start, LocalDate end);

    /**
     * 사례 첫날 현금으로 시작하는 실행을 접수한다 ({@code FRESH_CASH_FROM}).
     *
     * @param start {@code d0 − 워밍업 거래일}. 워밍업을 소진해야 d0부터 평가가 시작된다
     * @param d0 {@code scenarioParams.startDate}
     * @param end {@code d63}(미완성이면 {@code lastAvailable})
     */
    AcceptedRun acceptFreshCash(
            Long originRunId, long requesterId, LocalDate start, LocalDate d0, LocalDate end);

    /**
     * 실패한 실행을 004 재시도 경로로 다시 돌린다. 접근 판정은 호출자가 이미 끝냈다.
     *
     * <p>{@code runId}를 함께 받는 것은 004 저장소가 요청을 PK 로 찾는 조회를 두지 않아서다 — 그 실행에 달린 요청 가운데 이 것을 고른다.
     */
    AcceptedRun retry(Long runId, Long requestId, long requesterId);

    /**
     * 접수 결과.
     *
     * @param runId 링크가 기다릴 실행. 004가 같은 조건의 실행을 재사용하면 이미 끝난 실행일 수 있다
     * @param requestId 이 사용자의 분석 요청. {@code regime_link_use.created_request_id}에 남는다
     * @param completed 접수 시점에 이미 결과가 있는가. 참이면 링크는 기다리지 않고 바로 작업을 만든다
     */
    record AcceptedRun(Long runId, Long requestId, boolean completed) {}
}
