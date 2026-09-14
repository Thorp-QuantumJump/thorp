package com.ssafy.thorp.backtest.application.port;

/**
 * 워커에 제어 메시지를 보내는 출력 포트(spec 004 R-04). 현재 종류는 CANCEL 하나다.
 *
 * <p>fanout {@code backtest.control}이라 모든 워커가 받고, 그 실행을 돌리는 워커만 다음 거래일 처리 시점에 멈춘다. 구현은 {@code
 * infrastructure.messaging}에 두고 application 계층은 이 인터페이스만 안다.
 */
public interface BacktestControlPublisher {

    /**
     * 실행을 멈추라고 알린다. 발행에 실패하면 {@code BacktestPublishFailedException}(503).
     *
     * @param runId 실행의 외부 식별자(envelope {@code runId}). 사용자가 보는 요청 식별자가 아니다
     */
    void cancel(String runId);
}
