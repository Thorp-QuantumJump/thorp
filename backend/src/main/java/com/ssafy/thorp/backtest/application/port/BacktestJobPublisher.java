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
    void publish(EngineInput engineInput);
}
