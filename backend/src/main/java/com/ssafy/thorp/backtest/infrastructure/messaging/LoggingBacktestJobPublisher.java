package com.ssafy.thorp.backtest.infrastructure.messaging;

import com.ssafy.thorp.backtest.application.port.BacktestJobPublisher;
import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 임시 발행 구현. 브로커가 결정되기 전까지 컨텍스트 기동을 위해 로그만 남긴다.
 *
 * <p><b>프로덕션에 남기면 Job이 Worker에 전달되지 않는다.</b>
 *
 * <p>TODO(broker-decision): 브로커 결정 티켓에서 실제 Publisher로 교체하고 이 클래스를 삭제한다. 해당 티켓의 완료 조건에 "이 클래스 삭제"를
 * 포함한다.
 */
@Component
public class LoggingBacktestJobPublisher implements BacktestJobPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingBacktestJobPublisher.class);

    @Override
    public void publish(BacktestJob job) {
        log.warn(
                "[TEMP] Broker not configured. Job not delivered to worker: id={}, strategy={}, period={}~{}",
                job.getId(),
                job.getStrategy(),
                job.getPeriod().startDate(),
                job.getPeriod().endDate());
    }
}
