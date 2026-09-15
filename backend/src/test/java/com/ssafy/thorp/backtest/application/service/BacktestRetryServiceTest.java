package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.application.repository.BacktestRequestRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.domain.exception.BacktestNotFoundException;
import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.model.RunPlan;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.backtest.domain.vo.BacktestId;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

/** 재시도 유스케이스(T010). 접수를 다시 태우는 것이 핵심이라 {@link BacktestService}는 목으로 두고 "무엇을 넘겼는가"를 본다. */
class BacktestRetryServiceTest {

    private static final String BACKTEST_ID = "BT-20260914-000021";
    private static final long REQUESTER = 7L;
    private static final long OTHER = 8L;
    private static final Long REQUEST_ID = 21L;
    private static final Long RUN_ID = 11L;
    private static final String COST_PROFILE = "COST-2026-09";

    private final BacktestService backtestService = mock(BacktestService.class);
    private final BacktestRequestRepository requestRepository =
            mock(BacktestRequestRepository.class);
    private final BacktestRunRepository runRepository = mock(BacktestRunRepository.class);

    private final BacktestRetryService service =
            new BacktestRetryService(backtestService, requestRepository, runRepository);

    private static CreateBacktestCommand original() {
        return new CreateBacktestCommand(
                REQUESTER,
                ExecutionType.SINGLE,
                "SECTOR-ETF-2026-09",
                List.of(
                        new CreateBacktestCommand.AllocationItem(
                                "SEMICONDUCTOR", "091160.XKRX", new BigDecimal("0.4000")),
                        new CreateBacktestCommand.AllocationItem(
                                "BATTERY", "305720.XKRX", new BigDecimal("0.6000"))),
                "FIXED_WEIGHT",
                "1",
                "PARAM-2026-09",
                Map.of("rebalance", "MONTH_END"),
                LocalDate.of(2024, 1, 2),
                LocalDate.of(2024, 12, 31),
                new BigDecimal("10000000.00"),
                COST_PROFILE,
                "SPLIT-1",
                63,
                Scenario.FULL_PERIOD,
                null);
    }

    private static BacktestRun runIn(RunStatus status) {
        BacktestRun run =
                BacktestRun.restore(
                        RUN_ID,
                        BacktestId.from("BT-20260914-000011"),
                        "FIXED_WEIGHT",
                        "1",
                        Map.of("rebalance", "MONTH_END"),
                        List.of(new InstrumentId("091160.XKRX")),
                        new BacktestPeriod(LocalDate.of(2024, 1, 2), LocalDate.of(2024, 12, 31)),
                        new BigDecimal("10000000.00"),
                        PositionSizingMode.STRATEGY_DEFINED,
                        null,
                        null,
                        42,
                        status);
        return run.withPlan(
                new RunPlan(
                        "dedup",
                        "1",
                        COST_PROFILE,
                        "DATA-1",
                        LocalDate.of(2024, 4, 1),
                        LocalDate.of(2024, 12, 31),
                        63,
                        RequestSpecCodec.write(original(), Scenario.FULL_PERIOD, 63),
                        Map.of(),
                        null));
    }

    private static BacktestRequest requestOf(long requesterId, Instant cancelledAt) {
        return BacktestRequest.restore(
                REQUEST_ID,
                BacktestId.from(BACKTEST_ID),
                RUN_ID,
                requesterId,
                ExecutionType.SINGLE,
                ResultSource.NEW,
                null,
                cancelledAt,
                null,
                cancelledAt != null);
    }

    private void given(BacktestRequest request, RunStatus runStatus) {
        when(requestRepository.findByBacktestId(BACKTEST_ID)).thenReturn(Optional.of(request));
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(runIn(runStatus)));
    }

    private void givenCreateReturnsNew() {
        when(backtestService.create(any(), any()))
                .thenReturn(
                        new BacktestJobResult(
                                "BT-20260914-000031",
                                RunStatus.QUEUED,
                                ResultSource.NEW,
                                LocalDate.of(2024, 4, 1),
                                LocalDate.of(2024, 12, 31),
                                Map.of(),
                                null));
    }

    @Test
    @DisplayName("실패한 요청을 재시도하면 저장된 사본으로 접수를 다시 타고 원본 id 를 남긴다")
    void retriesAFailedRequest() {
        given(requestOf(REQUESTER, null), RunStatus.FAILED);
        givenCreateReturnsNew();

        BacktestJobResult result = service.retry(BACKTEST_ID, REQUESTER);

        ArgumentCaptor<CreateBacktestCommand> captor =
                ArgumentCaptor.forClass(CreateBacktestCommand.class);
        verify(backtestService).create(captor.capture(), eq(REQUEST_ID));
        assertThat(captor.getValue()).isEqualTo(original());
        assertThat(result.retryOf()).isEqualTo(BACKTEST_ID);
        assertThat(result.backtestId()).isEqualTo("BT-20260914-000031");
    }

    @Test
    @DisplayName("실행은 돌고 있어도 이 요청이 취소됐으면 재시도할 수 있다 — 판정은 요청 기준 상태다")
    void retriesACancelledRequestWhileTheRunContinues() {
        given(requestOf(REQUESTER, Instant.parse("2026-09-14T01:00:00Z")), RunStatus.RUNNING);
        givenCreateReturnsNew();

        assertThat(service.retry(BACKTEST_ID, REQUESTER).retryOf()).isEqualTo(BACKTEST_ID);
    }

    @ParameterizedTest
    @EnumSource(
            value = RunStatus.class,
            names = {"QUEUED", "RUNNING", "COMPLETED"})
    @DisplayName("끝나지 않았거나 성공한 분석은 409 NOT_RETRYABLE 이다")
    void rejectsNonRetryableStatuses(RunStatus status) {
        given(requestOf(REQUESTER, null), status);

        assertThatThrownBy(() -> service.retry(BACKTEST_ID, REQUESTER))
                .isInstanceOf(BusinessException.class)
                .extracting(exc -> ((BusinessException) exc).getErrorCode())
                .isEqualTo(ErrorCode.NOT_RETRYABLE);
        verify(backtestService, never()).create(any(), any());
    }

    @Test
    @DisplayName("남의 요청은 403 이 아니라 404 다 — 식별자의 존재를 알려 주지 않는다")
    void hidesOtherPeoplesRequests() {
        given(requestOf(OTHER, null), RunStatus.FAILED);

        assertThatThrownBy(() -> service.retry(BACKTEST_ID, REQUESTER))
                .isInstanceOf(BacktestNotFoundException.class);
        verify(backtestService, never()).create(any(), any());
    }
}
