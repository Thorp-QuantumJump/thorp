package com.ssafy.thorp.backtest.application.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;

/**
 * 작업 메시지 발행 실패. 브로커 장애는 도메인 규칙 위반이 아니라 유스케이스 수행 실패라 application 계층에 둔다.
 *
 * <p>생성 트랜잭션 안에서 던져 저장을 롤백한다. 큐에 들어가지 못한 Job이 QUEUED로 남지 않게 하는 것이 목적이다.
 */
public class BacktestPublishFailedException extends BusinessException {

    public BacktestPublishFailedException(String backtestId, Throwable cause) {
        super(
                ErrorCode.BROKER_UNAVAILABLE,
                "백테스트 작업을 큐에 넣지 못했습니다: backtestId=" + backtestId,
                cause);
    }
}
