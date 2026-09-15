package com.ssafy.thorp.backtest.application.event;

import com.ssafy.thorp.backtest.domain.model.RunStatus;

/**
 * 백테스트 실행 하나가 끝났다. {@code backtest} 모듈이 결과 저장을 커밋한 뒤 발행한다 (contracts/param-search-internal.md §3,
 * spec 007 research R6).
 *
 * <p><b>{@code backtest.events} 큐를 007이 다시 소비하지 않기 위해 존재한다.</b> 같은 큐에 컨슈머를 하나 더 붙이면 competing
 * consumers라 메시지가 분배되어 두 리스너가 각자 일부만 받는다.
 *
 * <p>이 모듈은 수신자를 알지 못한다 — 이벤트 타입만 공개하고 {@code paramsearch}를 참조하지 않는다. 수신자는
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}로 받는다. 커밋 전에 받으면 아직 없는 결과를 읽는다.
 *
 * @param backtestJobId 실행 행({@code backtest_run})의 PK. 후보가 이 값으로 자기 실행을 찾는다
 * @param backtestId 사용자에게 노출되는 식별자
 * @param status COMPLETED 또는 FAILED
 * @param errorCode {@code status=FAILED}일 때만, 아니면 null
 */
public record BacktestFinished(
        long backtestJobId, String backtestId, RunStatus status, String errorCode) {}
