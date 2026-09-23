package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.ssafy.thorp.backtest.application.exception.BacktestPublishFailedException;
import com.ssafy.thorp.backtest.application.port.BacktestControlPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 커밋 뒤 CANCEL 발행. 실패는 삼키고 T014 유예 감시가 다시 보낸다. */
class BacktestCancelDispatcherTest {

    private final BacktestControlPublisher publisher = mock(BacktestControlPublisher.class);
    private final BacktestCancelDispatcher dispatcher =
            new BacktestCancelDispatcher(
                    publisher, mock(org.springframework.context.ApplicationEventPublisher.class));

    @Test
    @DisplayName("취소 의도가 커밋되면 그 실행에 CANCEL 을 보낸다")
    void publishesCancelAfterCommit() {
        dispatcher.onCancelRequested(new BacktestCancelDispatcher.CancelRequested("BT-1"));

        verify(publisher).cancel("BT-1");
    }

    @Test
    @DisplayName("발행 실패는 밖으로 던지지 않는다 — 커밋은 끝났고 사용자는 202 를 받은 뒤다. 재발행은 유예 감시(T014) 몫")
    void publishFailureIsLoggedNotThrown() {
        doThrow(new BacktestPublishFailedException("BT-1", new RuntimeException("broker down")))
                .when(publisher)
                .cancel("BT-1");

        assertThatCode(
                        () ->
                                dispatcher.onCancelRequested(
                                        new BacktestCancelDispatcher.CancelRequested("BT-1")))
                .doesNotThrowAnyException();
    }
}
