package com.ssafy.thorp.backtest.infrastructure.listener;

import com.ssafy.thorp.backtest.application.event.BacktestFinished;
import com.ssafy.thorp.backtest.application.port.BacktestProgressStream;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 실행이 끝나면 진행 구독을 닫는다.
 *
 * <p><b>종결 경로마다 닫지 않고 {@link BacktestFinished} 하나를 받는 이유</b>: 실행이 끝나는 길이 다섯이다 — 워커 FINISHED, 작업 메시지
 * 전멸(DLQ), 발행 실패, 결과 재사용, 정체 판정. 각 자리에서 직접 닫으면 나중에 길이 하나 늘 때 조용히 빠지고, 그러면 구독자가 끝난 실행을 붙들고 있다가
 * 타임아웃으로만 풀린다. 이 이벤트는 다섯 경로 전부가 이미 발행한다.
 *
 * <p>{@code AFTER_COMMIT}인 이유: 커밋 전에 닫으면 화면이 최종 결과를 읽으러 갔을 때 아직 저장되지 않은 결과를 본다. 진행 곡선이 사라진 자리에 최종
 * 그래프가 오지 않는 상태가 된다.
 */
@Component
public class BacktestProgressStreamCloser {

    private final BacktestProgressStream stream;

    public BacktestProgressStreamCloser(BacktestProgressStream stream) {
        this.stream = stream;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBacktestFinished(BacktestFinished event) {
        stream.complete(event.backtestJobId(), event.status());
    }
}
