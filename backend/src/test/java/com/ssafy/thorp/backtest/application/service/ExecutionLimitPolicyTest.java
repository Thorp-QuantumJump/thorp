package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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

/** 접수 대기열 한도(spec 004 FR-006, R-19·R-20). 값이 아니라 "넘었는지"와 "무엇을 세는지"를 고정한다. */
class ExecutionLimitPolicyTest {

    private static final long REQUESTER = 7L;
    private static final int ANONYMOUS = 10;

    private final BacktestRunRepository runRepository = mock(BacktestRunRepository.class);
    private final ExecutionMetricRecorder metrics = mock(ExecutionMetricRecorder.class);

    private ExecutionLimitPolicy policyWith(int perUser, int total, boolean provisional) {
        return new ExecutionLimitPolicy(
                runRepository,
                metrics,
                new ExecutionLimits(
                        perUser,
                        total,
                        ANONYMOUS,
                        Duration.ofSeconds(1800),
                        Duration.ofSeconds(300),
                        Duration.ofSeconds(120),
                        provisional));
    }

    @Test
    @DisplayName("한도 안이면 통과한다")
    void admitsWithinLimits() {
        when(runRepository.countQueuedSingleByRequester(REQUESTER)).thenReturn(4L);
        when(runRepository.countQueuedSingle()).thenReturn(10L);

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
        verify(runRepository, never()).countQueuedSingle();
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
    @DisplayName("단건 큐 전체가 상한에 닿으면 사용자가 여유로워도 막는다")
    void rejectsWhenTheServerQueueIsFull() {
        when(runRepository.countQueuedSingleByRequester(REQUESTER)).thenReturn(0L);
        when(runRepository.countQueuedSingle()).thenReturn(50L);

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
    @DisplayName("단건이 아닌 실행은 한도의 대상이 아니다 — 다른 큐로 가고 적재량은 그쪽 발행 창과 워커 수가 정한다")
    void ignoresNonSingleEntirely(ExecutionType executionType) {
        // 한 큐였을 때는 세면 묶음이 영구 정지하고 안 세면 단건만 429 를 받았다(R-20). 큐를 나눈 뒤에는
        // 이 정책이 배치를 알 필요가 없다 — 잠금도 조회도 하지 않는다.
        policyWith(5, 50, false).requireAdmission(REQUESTER, executionType);

        verify(runRepository, never()).lockAdmission(anyString());
        verify(runRepository, never()).countQueuedSingleByRequester(anyLong());
        verify(runRepository, never()).countQueuedSingle();
    }

    @Test
    @DisplayName("세는 구간을 잠그고, 사용자 키를 전체 키보다 먼저 잡는다 — 순서가 엇갈리면 교착한다")
    void locksUserKeyBeforeTotalKey() {
        when(runRepository.countQueuedSingleByRequester(REQUESTER)).thenReturn(0L);
        when(runRepository.countQueuedSingle()).thenReturn(0L);

        policyWith(5, 50, false).requireAdmission(REQUESTER, ExecutionType.SINGLE);

        InOrder order = Mockito.inOrder(runRepository);
        order.verify(runRepository).lockAdmission("backtest-admission:user:" + REQUESTER);
        order.verify(runRepository).countQueuedSingleByRequester(REQUESTER);
        order.verify(runRepository).lockAdmission("backtest-admission:total");
        order.verify(runRepository).countQueuedSingle();
    }

    @Test
    @DisplayName("비로그인 요청은 사용자별 한도 대신 비로그인 몫을 먼저 세고, 그다음 전체 한도를 센다(spec 005 T012)")
    void anonymousRequestCountsTheAnonymousShareThenTheTotal() {
        when(runRepository.countQueuedSingleAnonymous()).thenReturn(9L);
        when(runRepository.countQueuedSingle()).thenReturn(10L);

        assertThatCode(() -> policyWith(5, 50, false).requireAdmission(null, ExecutionType.SINGLE))
                .doesNotThrowAnyException();

        verify(runRepository, never()).countQueuedSingleByRequester(anyLong());
        InOrder order = Mockito.inOrder(runRepository);
        order.verify(runRepository).lockAdmission("backtest-admission:anonymous");
        order.verify(runRepository).countQueuedSingleAnonymous();
        order.verify(runRepository).lockAdmission("backtest-admission:total");
        order.verify(runRepository).countQueuedSingle();
    }

    @Test
    @DisplayName("비로그인 몫이 차면 전체가 여유로워도 429 ANONYMOUS 다 — 비로그인 클라이언트 하나가 전체 대기열을 채우지 못한다")
    void anonymousShareIsBoundedBeforeTheTotal() {
        when(runRepository.countQueuedSingleAnonymous()).thenReturn(10L);

        assertThatThrownBy(
                        () -> policyWith(5, 50, false).requireAdmission(null, ExecutionType.SINGLE))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        exc ->
                                assertThat(((BusinessException) exc).getDetails())
                                        .containsEntry("reason", "ANONYMOUS")
                                        .containsEntry("queued", 10L)
                                        .containsEntry("limit", ANONYMOUS));
        verify(runRepository, never()).countQueuedSingle();
        verify(metrics).limitRejected(null, ExecutionType.SINGLE, "ANONYMOUS");
    }

    @Test
    @DisplayName("로그인 요청은 비로그인 몫을 세지 않는다 — 비로그인 몫이 가득 차도 로그인 사용자는 통과한다")
    void loggedInRequestIgnoresTheAnonymousShare() {
        when(runRepository.countQueuedSingleByRequester(REQUESTER)).thenReturn(0L);
        when(runRepository.countQueuedSingle()).thenReturn(10L);

        assertThatCode(
                        () ->
                                policyWith(5, 50, false)
                                        .requireAdmission(REQUESTER, ExecutionType.SINGLE))
                .doesNotThrowAnyException();

        verify(runRepository, never()).countQueuedSingleAnonymous();
    }

    @Test
    @DisplayName("비로그인 요청도 전체 한도에는 막힌다 — 상한 장치 없이 열지 않는다(원칙 IX)")
    void anonymousRequestIsStillBoundByTheTotal() {
        when(runRepository.countQueuedSingleAnonymous()).thenReturn(0L);
        when(runRepository.countQueuedSingle()).thenReturn(50L);

        assertThatThrownBy(
                        () -> policyWith(5, 50, false).requireAdmission(null, ExecutionType.SINGLE))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        exc ->
                                assertThat(((BusinessException) exc).getDetails())
                                        .containsEntry("reason", "TOTAL"));
        verify(metrics).limitRejected(null, ExecutionType.SINGLE, "TOTAL");
    }
}
