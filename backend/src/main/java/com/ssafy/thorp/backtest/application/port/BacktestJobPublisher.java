package com.ssafy.thorp.backtest.application.port;

import com.ssafy.thorp.backtest.application.port.message.EngineInput;

/**
 * 백테스트 작업 메시지를 Worker에 전달하는 출력 포트.
 *
 * <p>조립(고정 정책 적용)은 application이 끝내고 여기에는 완성된 본문만 넘긴다. 구현은 {@code infrastructure.messaging}에 두고
 * application 계층은 이 인터페이스만 안다.
 */
public interface BacktestJobPublisher {

    /**
     * 발행에 실패하면 {@code BacktestPublishFailedException}을 던진다.
     *
     * <p>동기 오류(연결 불가·채널 오류)만 잡는다. publisher confirm과 returns가 없으므로 unroutable 메시지와 비동기 nack는 감지하지
     * 못한다. outbox 도입 전까지의 알려진 한계다.
     */
    /**
     * @param publishAttempt 이 발행의 시도 번호({@code backtest_run.publish_count} 를 올린 직후의 값). 메시지에 실려
     *     나가고, 그 메시지가 DLQ 로 끝나면 백엔드가 "어느 시도가 죽었는지"를 이 번호로 구분한다(R-18 ③) — 없으면 재전달된 DLQ 메시지를 다른 시도의
     *     죽음으로 잘못 세어 살아 있는 실행을 끊는다
     */
    void publish(EngineInput engineInput, int publishAttempt);
}
