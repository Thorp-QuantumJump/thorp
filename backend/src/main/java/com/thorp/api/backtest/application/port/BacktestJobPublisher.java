package com.thorp.api.backtest.application.port;

import com.thorp.api.backtest.domain.model.BacktestJob;

/**
 * 백테스트 Job을 Worker에 전달하는 출력 포트.
 *
 * <p>브로커(RabbitMQ/Kafka)는 아직 결정되지 않았다. 구현은 {@code infrastructure.messaging}에 두고 application 계층은 이
 * 인터페이스만 안다.
 */
public interface BacktestJobPublisher {

    void publish(BacktestJob job);
}
