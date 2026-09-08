package com.ssafy.thorp.backtest.application.port;

import com.ssafy.thorp.backtest.application.port.message.BacktestEvent;
import com.ssafy.thorp.backtest.application.port.message.ProgressPayload;

/**
 * PROGRESS 이벤트를 구독자에게 전달하는 포트. DB에 저장하지 않는다.
 *
 * <p>envelope을 통째로 받는다. 실시간 전달(SSE)은 {@code eventId}(중복 감지)·{@code executionId}·{@code sequence}(누락
 * 감지)를 모두 필요로 하므로 payload만 넘기면 구현 시점에 포트를 다시 고쳐야 한다.
 */
public interface BacktestProgressNotifier {

    /** RUNNING 상태인 Job의 진행 이벤트만 들어온다. 모르는 Job·끝난 Job의 늦은 PROGRESS는 서비스가 먼저 걸러낸다. */
    void notifyProgress(BacktestEvent<ProgressPayload> event);
}
