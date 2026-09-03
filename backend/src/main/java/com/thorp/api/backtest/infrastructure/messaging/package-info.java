/**
 * 백테스트 Job 발행({@code application.port.BacktestJobPublisher}) 구현 위치.
 *
 * <p>브로커(RabbitMQ 예정, Kafka는 설치만)가 결정되면 실제 구현을 여기에 두고 {@code LoggingBacktestJobPublisher}를 삭제한다.
 */
package com.thorp.api.backtest.infrastructure.messaging;
