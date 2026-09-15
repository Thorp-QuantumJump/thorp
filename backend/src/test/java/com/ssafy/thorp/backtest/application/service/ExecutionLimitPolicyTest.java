package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.backtest.application.port.ExecutionLimits;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.Mockito;

/** 접수 대기열 한도(spec 004 FR-006, R-19). 값이 아니라 "넘었는지"와 "무엇을 세는지"를 고정한다. */
class ExecutionLimitPolicyTest {

    private static final long REQUESTER = 7L;

    private final BacktestRunRepository runRepository = mock(BacktestRunRepository.class);

    private ExecutionLimitPolicy policyWith(int perUser, int total, boolean provisional) {
        return new ExecutionLimitPolicy(
                runRepository,
                new ExecutionLimits(
                        perUser,
                        total,
                        Duration.ofSeconds(1800),
                        Duration.ofSeconds(300),
                        Duration.ofSeconds(120),
                        provisional));
    }

    @Test
    @DisplayName("한도 안이면 통과한다")
    void admitsWithinLimits() {
        when(runRepository.countQueuedSingleByRequester(REQUESTER)).thenReturn(4L);
        when(runRepository.countQueued()).thenReturn(10L);

        assertThatCode(
                        () ->
                                policyWith(5, 50, false)
                                        .requireAdmission(REQUESTER, ExecutionType.SINGLE))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("사용자 대기 수가 상한에 닿으면 429 EXECUTION_LIMIT 이고 본문에 판단 근거가 실린다")
    void rejectsWhenTheUserQueueIsFull() {
        when(runRepository.countQueuedSingleByRequester(REQUESTER)).thenReturn(5L);

        BusinessException thrown =
                (BusinessException)
                        org.assertj.core.api.Assertions.catchThrowable(
                                () ->
                                        policyWith(5, 50, false)
                                                .requireAdmission(REQUESTER, ExecutionType.SINGLE));

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.EXECUTION_LIMIT);
        Map<String, Object> details = thrown.getDetails();
        assertThat(details)
                .containsEntry("reason", "PER_USER")
                .containsEntry("queued", 5L)
                .containsEntry("limit", 5)
                // 재시도 시점은 추정하지 않고 실행 시간 상한을 준다 — 그 안에는 반드시 자리가 난다.
                .containsEntry("retryAfterSeconds", 1800L);
        assertThat(details).doesNotContainKey("provisional");
        // 사용자 한도에서 막혔으면 전체는 세지 않는다.
        verify(runRepository, never()).countQueued();
    }

    @Test
    @DisplayName("값이 임시값이면 그 사실을 응답에 싣는다 — 확정된 서비스 정책으로 오해하면 안 된다")
    void marksProvisionalLimits() {
        when(runRepository.countQueuedSingleByRequester(REQUESTER)).thenReturn(5L);

        assertThatThrownBy(
                        () ->
                                policyWith(5, 50, true)
                                        .requireAdmission(REQUESTER, ExecutionType.SINGLE))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        exc ->
                                assertThat(((BusinessException) exc).getDetails())
                                        .containsEntry("provisional", true));
    }

    @Test
    @DisplayName("전체 대기 수가 상한에 닿으면 사용자가 여유로워도 막는다")
    void rejectsWhenTheServerQueueIsFull() {
        when(runRepository.countQueuedSingleByRequester(REQUESTER)).thenReturn(0L);
        when(runRepository.countQueued()).thenReturn(50L);

        assertThatThrownBy(
                        () ->
                                policyWith(5, 50, false)
                                        .requireAdmission(REQUESTER, ExecutionType.SINGLE))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        exc ->
                                assertThat(((BusinessException) exc).getDetails())
                                        .containsEntry("reason", "TOTAL"));
    }

    @ParameterizedTest
    @EnumSource(
            value = ExecutionType.class,
            names = {"SEARCH_BUNDLE", "REGIME_SECTOR", "REGIME_TOTAL"})
    @DisplayName("단건이 아닌 실행은 사용자별 한도에서 세지 않는다 — 적재량은 그쪽 발행 창이 통제한다")
    void doesNotCountNonSingleAgainstTheUserLimit(ExecutionType executionType) {
        // 세었다면 0 < 5 라 통과하겠지만, 애초에 묻지 않는 것이 요점이다. 두 곳에서 막으면
        // 007 이 창을 키운 날 탐색이 접수에서 거절당해 묶음이 조용히 멈춘다.
        when(runRepository.countQueued()).thenReturn(0L);

        policyWith(5, 50, false).requireAdmission(REQUESTER, executionType);

        verify(runRepository, never()).countQueuedSingleByRequester(REQUESTER);
        verify(runRepository, never()).lockAdmission("backtest-admission:user:" + REQUESTER);
        // 전체 한도는 종류를 가리지 않는다 — 그쪽은 서버 보호다.
        verify(runRepository).countQueued();
    }

    @ParameterizedTest
    @EnumSource(
            value = ExecutionType.class,
            names = {"SEARCH_BUNDLE", "REGIME_SECTOR", "REGIME_TOTAL"})
    @DisplayName("전체 한도가 차도 단건이 아닌 실행은 거절하지 않는다 — 끊으면 그 묶음이 다시 깨어나지 못한다")
    void doesNotRejectNonSingleOnTheTotalLimit(ExecutionType executionType) {
        // 007 은 완료 이벤트로만 다음 후보를 내보낸다. 마지막 진행 후보가 끝난 순간 거절당하면
        // 남은 후보가 PENDING 인 채로 깨울 신호가 없어져 묶음이 영구히 멈춘다.
        when(runRepository.countQueued()).thenReturn(50L);

        assertThatCode(() -> policyWith(5, 50, false).requireAdmission(REQUESTER, executionType))
                .doesNotThrowAnyException();
        // 세기는 센다 — 탐색이 서버를 채우면 단건이 먼저 막힌다.
        verify(runRepository).countQueued();
    }

    @Test
    @DisplayName("세는 구간을 잠그고, 사용자 키를 전체 키보다 먼저 잡는다 — 순서가 엇갈리면 교착한다")
    void locksUserKeyBeforeTotalKey() {
        when(runRepository.countQueuedSingleByRequester(REQUESTER)).thenReturn(0L);
        when(runRepository.countQueued()).thenReturn(0L);

        policyWith(5, 50, false).requireAdmission(REQUESTER, ExecutionType.SINGLE);

        InOrder order = Mockito.inOrder(runRepository);
        order.verify(runRepository).lockAdmission("backtest-admission:user:" + REQUESTER);
        order.verify(runRepository).countQueuedSingleByRequester(REQUESTER);
        order.verify(runRepository).lockAdmission("backtest-admission:total");
        order.verify(runRepository).countQueued();
    }
}
