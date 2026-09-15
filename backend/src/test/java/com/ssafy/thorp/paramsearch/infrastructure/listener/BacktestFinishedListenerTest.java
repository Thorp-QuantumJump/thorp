package com.ssafy.thorp.paramsearch.infrastructure.listener;

import static org.mockito.Mockito.verify;

import com.ssafy.thorp.backtest.application.event.BacktestFinished;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.paramsearch.application.service.ParamSearchProgressService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 004 의 실행 종결을 007 의 후보 결과로 번역하는 경계를 고정한다.
 *
 * <p>여기서 틀리면 증상이 멀리서 나타난다 — 중간까지만 계산된 후보가 정상 완료로 집계되고, 그 성과가 다른 후보와 나란히 비교되어 선정까지 간다. 실행 행만 검사하는
 * 테스트는 이 누락을 잡지 못한다(R-19).
 */
@ExtendWith(MockitoExtension.class)
class BacktestFinishedListenerTest {

    private static final long JOB_ID = 42L;
    private static final String BACKTEST_ID = "bt_0001";

    @Mock private ParamSearchProgressService progressService;

    @InjectMocks private BacktestFinishedListener listener;

    @Test
    @DisplayName("COMPLETED 만 정상 완료로 넘긴다")
    void completedIsNotAFailure() {
        listener.onBacktestFinished(finished(RunStatus.COMPLETED, null));

        verify(progressService).candidateFinished(JOB_ID, false, null);
    }

    @Test
    @DisplayName("FAILED 는 엔진 오류 코드를 그대로 넘긴다")
    void failedCarriesTheEngineErrorCode() {
        listener.onBacktestFinished(finished(RunStatus.FAILED, "ENGINE_ERROR"));

        verify(progressService).candidateFinished(JOB_ID, true, "ENGINE_ERROR");
    }

    @Test
    @DisplayName("실행 시간 상한으로 끊긴 CANCELLED 는 실패로 넘긴다 — 부분 결과는 선정 대상이 아니다")
    void cancelledByExecutionLimitIsAFailure() {
        listener.onBacktestFinished(finished(RunStatus.CANCELLED, "EXECUTION_LIMIT"));

        verify(progressService).candidateFinished(JOB_ID, true, "EXECUTION_LIMIT");
    }

    @Test
    @DisplayName("사유 없는 CANCELLED 도 실패로 넘기되 ENGINE_ERROR 로 뭉개지 않는다")
    void cancelledWithoutReasonIsNotAnEngineError() {
        listener.onBacktestFinished(finished(RunStatus.CANCELLED, null));

        verify(progressService).candidateFinished(JOB_ID, true, "CANCELLED");
    }

    private static BacktestFinished finished(RunStatus status, String errorCode) {
        return new BacktestFinished(JOB_ID, BACKTEST_ID, status, errorCode);
    }
}
