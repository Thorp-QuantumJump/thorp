package com.ssafy.thorp.paramsearch.infrastructure.launcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.command.PinnedVersions;
import com.ssafy.thorp.backtest.application.repository.BacktestRequestRepository;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.application.service.BacktestService;
import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.model.PublicResultToken;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.backtest.domain.vo.BacktestId;
import com.ssafy.thorp.paramsearch.application.port.ParamSearchSnapshot;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import com.ssafy.thorp.paramsearch.domain.vo.GridCoordinates;
import com.ssafy.thorp.paramsearch.domain.vo.ParameterCombination;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 007 묶음 후보는 버전이 고정된 요청으로 004 에 들어간다(spec 004 T029).
 *
 * <p>묶음은 접수 때 규칙·등록표 버전을 스냅샷에 고정하고 후보를 나중에 하나씩 넣는다. 새 요청으로 넣으면 그 사이 규칙이 새 버전으로 대체된 날 남은 후보가 409 로
 * 막히고, 그 예외가 결과 반영 트랜잭션을 되돌려 묶음이 멈춘다.
 */
class CandidateJobLauncherImplTest {

    private static final String BACKTEST_ID = "BT-20260919-000031";
    private static final long OWNER = 7L;

    private final BacktestService backtestService = mock(BacktestService.class);
    private final BacktestRequestRepository requestRepository =
            mock(BacktestRequestRepository.class);
    private final CandidateJobLauncherImpl launcher =
            new CandidateJobLauncherImpl(backtestService, requestRepository);

    @Test
    @DisplayName("후보는 스냅샷의 규칙·등록표 버전 그대로 버전이 고정된 요청으로 접수된다 — 새 요청 경로를 타지 않는다")
    void candidatesAreAdmittedWithTheSnapshotVersionsPinned() {
        // 스텁 인자는 when(...) 밖에서 만든다. 안에서 만들다 예외가 나면 끝나지 않은 스텁이 다음 테스트로 번진다.
        BacktestJobResult accepted = accepted();
        BacktestRequest request = request(77L);
        when(backtestService.createPinned(any(), any())).thenReturn(accepted);
        when(requestRepository.findByBacktestId(BACKTEST_ID)).thenReturn(Optional.of(request));

        long runId =
                launcher.launchCandidate(
                        snapshot(),
                        SearchCandidate.pending(
                                1,
                                "STRATEGY_A",
                                ParameterCombination.of(Map.of("x", 1)),
                                GridCoordinates.of(Map.of("x", 0))));

        ArgumentCaptor<CreateBacktestCommand> command =
                ArgumentCaptor.forClass(CreateBacktestCommand.class);
        verify(backtestService).createPinned(command.capture(), eq(PinnedVersions.searchBundle()));
        verify(backtestService, never()).create(any());
        assertThat(command.getValue().rulesVersion()).isEqualTo("1");
        assertThat(command.getValue().registryVersion()).isEqualTo("FIXTURE-1");
        assertThat(command.getValue().executionType()).isEqualTo(ExecutionType.SEARCH_BUNDLE);
        assertThat(runId).isEqualTo(77L);
    }

    private static BacktestJobResult accepted() {
        return new BacktestJobResult(
                BACKTEST_ID,
                RunStatus.QUEUED,
                ResultSource.NEW,
                LocalDate.of(2020, 1, 2),
                LocalDate.of(2026, 8, 31),
                Map.of(),
                null,
                "0123456789abcdef0123456789abcdef");
    }

    private static BacktestRequest request(long runId) {
        return BacktestRequest.restore(
                31L,
                BacktestId.from(BACKTEST_ID),
                runId,
                OWNER,
                ExecutionType.SEARCH_BUNDLE,
                ResultSource.NEW,
                null,
                null,
                null,
                false,
                PublicResultToken.generate(),
                null);
    }

    private static ParamSearchSnapshot snapshot() {
        return new ParamSearchSnapshot(
                1L,
                "PS-20260919-000001",
                OWNER,
                List.of(new ParamSearchSnapshot.UniverseEntry("SECTOR", "091160.XKRX", null)),
                "MAPPING-1",
                LocalDate.of(2020, 1, 2),
                LocalDate.of(2026, 8, 31),
                new BigDecimal("10000000.00"),
                "cost-1",
                null,
                20,
                "FIXTURE-1",
                Map.of("STRATEGY_A", "1"),
                null,
                Map.of());
    }
}
