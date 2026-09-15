package com.ssafy.thorp.paramsearch.infrastructure.listener;

import com.ssafy.thorp.backtest.application.event.BacktestFinished;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.paramsearch.application.service.ParamSearchProgressService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 004의 실행 완료를 받아 묶음 진행을 전진시킨다(contracts/param-search-internal.md §3, research R6).
 *
 * <p><b>{@code backtest.events} 큐를 다시 소비하지 않는다.</b> 같은 큐에 컨슈머를 하나 더 붙이면 competing consumers라 메시지가
 * 분배되어 두 리스너가 각자 일부만 받는다. 그래서 인메모리 도메인 이벤트를 쓴다.
 *
 * <p>{@code AFTER_COMMIT}인 이유: 커밋 전에 받으면 아직 저장되지 않은 결과를 읽는다. 후보가 "완료"인데 성과가 비어 있는 상태가 되고, 원인이 007
 * 코드에 없어서 찾기 어렵다.
 *
 * <p>묶음에 속하지 않은 Job이 대부분이다(단일 실행). 못 찾으면 <b>경고 없이</b> 무시한다 — 경고를 남기면 로그가 그것으로 덮인다.
 */
@Component
@ConditionalOnProperty(prefix = "thorp.param-search", name = "enabled", havingValue = "true")
public class BacktestFinishedListener {

    private static final Logger log = LoggerFactory.getLogger(BacktestFinishedListener.class);

    private final ParamSearchProgressService progressService;

    public BacktestFinishedListener(ParamSearchProgressService progressService) {
        this.progressService = progressService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBacktestFinished(BacktestFinished event) {
        boolean belongsToSearch =
                progressService.candidateFinished(
                        event.backtestJobId(),
                        event.status() == RunStatus.FAILED,
                        event.errorCode());
        if (belongsToSearch) {
            log.debug(
                    "param search candidate finished: backtestJobId={} status={}",
                    event.backtestJobId(),
                    event.status());
        }
    }
}
