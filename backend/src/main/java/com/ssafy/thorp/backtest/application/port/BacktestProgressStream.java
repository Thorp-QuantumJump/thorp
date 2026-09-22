package com.ssafy.thorp.backtest.application.port;

import com.ssafy.thorp.backtest.application.port.message.ProgressPayload;
import com.ssafy.thorp.backtest.domain.model.RunStatus;

/**
 * 실행 중 진행 시계열을 구독자에게 흘린다(spec 004 R-05).
 *
 * <p>PROGRESS 이벤트의 {@code portfolioSeries}는 DB에 저장하지 않는다 — 저장하면 최종 결과의 시계열과 두 벌이 되고 둘이 어긋날 수
 * 있다({@link com.ssafy.thorp.backtest.domain.model.RunProgress} 참고). 대신 이 경로로만 흘리고, 받을 사람이 없으면 버린다.
 *
 * <p><b>구독을 콜백으로 정의한 이유</b>: 전달 수단(SSE)은 웹 계층의 일이다. 이 포트가 {@code SseEmitter}를 알면 진행 전달이 HTTP에 묶여
 * 테스트에서도 웹 스택이 필요해진다. 구현({@code infrastructure})은 실행별 구독자 목록만 들고 있고, 감싸는 쪽({@code presentation})이
 * 자기 전달 수단으로 옮긴다.
 *
 * <p><b>키는 실행 행의 PK다.</b> 워커 이벤트의 {@code runId}(외부 문자열)가 아니다. 여러 요청이 한 실행에 합류하므로, 같은 실행을 보는 요청 둘이
 * 같은 진행을 받아야 한다. 공개 토큰은 요청에 붙어 있고 요청은 실행 PK를 들고 있다.
 */
public interface BacktestProgressStream {

    /** 이번 구간에서 새로 계산한 포인트를 구독자 전원에게 보낸다. 구독자가 없으면 아무것도 하지 않는다. */
    void publish(long runId, ProgressPayload payload);

    /** 실행이 끝났다. 구독자에게 알리고 구독을 닫는다. 더 올 포인트가 없다는 뜻이다. */
    void complete(long runId, RunStatus status);

    /**
     * 구독을 건다. 반환값을 닫으면 구독이 해제된다 — 화면을 떠났거나 전달이 끊긴 경우다.
     *
     * <p>구독 시점에 이미 끝난 실행이면 {@link Listener#onComplete}가 오지 않을 수 있다. 부르는 쪽이 상태를 먼저 확인한다.
     */
    Subscription subscribe(long runId, Listener listener);

    /** 구독 해제 손잡이. 두 번 닫아도 문제가 없어야 한다. */
    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * 구독자가 받는 알림. 두 메서드 모두 발행하는 스레드(브로커 리스너)에서 불린다 — 오래 걸리는 일을 하지 않는다.
     *
     * <p>구현이 던지는 예외는 그 구독자만 떨어뜨리고, 다른 구독자와 이벤트 ACK 에 영향을 주지 않는다.
     */
    interface Listener {
        void onProgress(ProgressPayload payload);

        void onComplete(RunStatus status);
    }
}
