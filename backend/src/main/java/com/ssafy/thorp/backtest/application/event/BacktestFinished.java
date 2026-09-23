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
 * @param status COMPLETED, FAILED, 또는 CANCELLED. <b>CANCELLED 도 끝난 것이다</b> — 실행 시간 상한으로 끊긴 실행은 부분
 *     결과를 남기고 CANCELLED 로 끝나므로, 받는 쪽은 COMPLETED 가 아닌 것을 모두 "결과를 믿을 수 없는 종결"로 다루어야 한다(R-19)
 * @param errorCode 실행 행에 남은 사유. FAILED 의 엔진 오류 코드이거나, 실행 시간 상한으로 끊긴 CANCELLED 의 {@code
 *     EXECUTION_LIMIT} 이다. 정상 완료와 사용자가 스스로 멈춘 취소는 null
 */
public record BacktestFinished(
        long backtestJobId, String backtestId, RunStatus status, String errorCode) {}
