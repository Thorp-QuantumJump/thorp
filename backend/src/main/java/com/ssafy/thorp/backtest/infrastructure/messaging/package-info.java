/**
 * 백테스트 브로커 연동. 토폴로지·직렬화 설정({@code BacktestRabbitConfig})과 작업 발행 ({@code
 * application.port.BacktestJobPublisher}) 구현이 있다.
 *
 * <p>결과 이벤트 수신 Consumer도 이 패키지에 들어온다.
 */
package com.ssafy.thorp.backtest.infrastructure.messaging;
