package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 커밋은 됐는데 브로커에 닿지 못한 작업을 다시 보낸다(spec 004 research R-18).
 *
 * <p>대상은 {@code status=QUEUED AND published_at IS NULL AND queued_at < now - 유예}인 실행이다. 유예를 두는 이유는
 * <b>방금 접수돼 아직 발행 중인 실행</b>과 구분하기 위해서다 — 유예가 없으면 정상 경로와 경쟁해 같은 작업을 두 번 보낸다.
 *
 * <p>중복 발행 자체는 안전하다. 입력이 같으면 결과가 같고(결정성), 백엔드가 종결 규칙으로 두 번째 결과를 버린다(R-18). 그래도 쓸데없는 계산이므로 유예를 둔다.
 */
@Component
@ConditionalOnProperty(
        name = "thorp.backtest.republish.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class JobRepublisher {

    private static final Logger log = LoggerFactory.getLogger(JobRepublisher.class);

    private final BacktestRunRepository repository;
    private final BacktestJobDispatcher dispatcher;
    private final Clock clock;
    private final Duration graceAfterQueued;
    private final int batchSize;

    public JobRepublisher(
            BacktestRunRepository repository,
            BacktestJobDispatcher dispatcher,
            Clock clock,
            @Value("${thorp.backtest.republish.grace:PT1M}") Duration graceAfterQueued,
            @Value("${thorp.backtest.republish.batch-size:50}") int batchSize) {
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.graceAfterQueued = graceAfterQueued;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${thorp.backtest.republish.interval:PT30S}")
    public void republishUnpublished() {
        List<BacktestRun> stale =
                repository.findUnpublishedQueued(
                        clock.instant().minus(graceAfterQueued), batchSize);
        if (stale.isEmpty()) {
            return;
        }
        log.info("발행되지 않은 대기 작업 {}건을 재발행합니다.", stale.size());
        for (BacktestRun run : stale) {
            dispatcher.republish(run.getId());
        }
    }
}
