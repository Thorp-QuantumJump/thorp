package com.ssafy.thorp.backtest.infrastructure.messaging;

import com.ssafy.thorp.backtest.application.port.BacktestProgressNotifier;
import com.ssafy.thorp.backtest.application.port.message.BacktestEvent;
import com.ssafy.thorp.backtest.application.port.message.ProgressPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 임시 구현. 진행 이벤트를 로그로만 남긴다.
 *
 * <p>TODO(진행 상황 SSE 작업): SSE 전파 구현으로 교체하고 이 클래스를 삭제한다. 그 작업의 완료 조건에 이 클래스 삭제를 포함한다.
 *
 * <p>정상 동작이고 20거래일마다 한 번 오므로 로그 레벨은 debug다.
 */
@Component
class LoggingBacktestProgressNotifier implements BacktestProgressNotifier {

    private static final Logger log =
            LoggerFactory.getLogger(LoggingBacktestProgressNotifier.class);

    @Override
    public void notifyProgress(BacktestEvent<ProgressPayload> event) {
        log.debug(
                "backtest progress: backtestId={} executionId={} sequence={} {}/{} trading days",
                event.backtestId(),
                event.executionId(),
                event.sequence(),
                event.payload().completedTradingDays(),
                event.payload().totalTradingDays());
    }
}
