package com.ssafy.thorp.paramsearch.infrastructure.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ssafy.thorp.backtest.application.event.BacktestFinished;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.paramsearch.application.service.ParamSearchProgressService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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

    /** 넘긴 일감을 붙잡아 둔다 — 넘겼는지와 무엇을 넘겼는지를 따로 본다. */
    private final List<Runnable> handedOff = new ArrayList<>();

    private final TaskExecutor executor = handedOff::add;

    private BacktestFinishedListener listener;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        listener = new BacktestFinishedListener(progressService, executor);
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

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

    @Test
    @DisplayName("처리 중에 도착한 완료는 겹쳐 처리하지 않고 앞의 처리가 끝난 뒤에 이어 간다")
    void chainedResultsAreQueuedInsteadOfNested() {
        // 후보를 전진시키면 다음 후보가 발행되고, 그 조합이 이미 계산돼 있으면 004 가 그 자리에서
        // 완료를 다시 알린다(재사용). 그 알림을 곧바로 처리하면 후보마다 트랜잭션이 겹겹이 열려
        // 커넥션 풀이 마르고 마지막 후보가 DISPATCHED 로 남는다(S15P21E205-417).
        List<Long> order = new ArrayList<>();
        int[] depth = {0};
        given(progressService.candidateFinished(anyLong(), anyBoolean(), any()))
                .willAnswer(
                        invocation -> {
                            long jobId = invocation.getArgument(0);
                            depth[0]++;
                            assertThat(depth[0]).as("겹쳐 처리하면 깊이가 쌓인다").isEqualTo(1);
                            order.add(jobId);
                            if (jobId == JOB_ID) {
                                listener.onBacktestFinished(
                                        new BacktestFinished(
                                                JOB_ID + 1, "bt_0002", RunStatus.COMPLETED, null));
                            }
                            depth[0]--;
                            return true;
                        });

        listener.onBacktestFinished(finished(RunStatus.COMPLETED, null));

        assertThat(order).as("이어진 완료도 빠짐없이 처리한다").containsExactly(JOB_ID, JOB_ID + 1);
    }

    @Test
    @DisplayName("응답을 기다리는 요청 스레드에서는 그 자리에서 돌리지 않고 진행 실행기에 넘긴다")
    void resultsOnARequestThreadAreHandedOff() {
        // 접수 요청 스레드에서 묶음을 끝까지 돌리면 POST 응답이 그만큼 늦는다. 계약은 202 로 바로
        // 끝나고 진행은 화면의 3초 폴링으로 보이는 것이다(FR-008).
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));

        listener.onBacktestFinished(finished(RunStatus.COMPLETED, null));

        verifyNoInteractions(progressService);
        assertThat(handedOff).hasSize(1);

        // 넘긴 일감은 실행기 스레드에서 같은 일을 한다.
        handedOff.get(0).run();
        verify(progressService).candidateFinished(JOB_ID, false, null);
    }

    private static BacktestFinished finished(RunStatus status, String errorCode) {
        return new BacktestFinished(JOB_ID, BACKTEST_ID, status, errorCode);
    }
}
