package com.ssafy.thorp.paramsearch.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.paramsearch.application.port.CandidateJobLauncher;
import com.ssafy.thorp.paramsearch.application.port.ParamSearchSnapshot;
import com.ssafy.thorp.paramsearch.application.port.SearchLimits;
import com.ssafy.thorp.paramsearch.application.repository.ParamSearchRepository;
import com.ssafy.thorp.paramsearch.application.repository.SearchCandidateRepository;
import com.ssafy.thorp.paramsearch.domain.model.CandidateStatus;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import com.ssafy.thorp.paramsearch.domain.vo.GridCoordinates;
import com.ssafy.thorp.paramsearch.domain.vo.ParameterCombination;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 발행 창 불변식(T041, 내부 계약 §4 불변식 1).
 *
 * <p>확인하는 것 하나다 — <b>{@code DISPATCHED} 후보 수가 창을 넘지 않는다.</b> 넘기면 한 묶음이 {@code backtest.jobs}를 독점해
 * 다른 사용자의 단일 실행이 뒤로 밀린다.
 */
@ExtendWith(MockitoExtension.class)
class CandidateDispatcherTest {

    private static final long SEARCH_ID = 7L;
    private static final int WINDOW = 4;

    @Mock private CandidateJobLauncher launcher;
    @Mock private ParamSearchRepository paramSearchRepository;
    @Mock private SearchCandidateRepository candidateRepository;

    private CandidateDispatcher dispatcher;

    @BeforeEach
    void createDispatcher() {
        dispatcher =
                new CandidateDispatcher(
                        launcher,
                        paramSearchRepository,
                        candidateRepository,
                        new SearchLimits(120, WINDOW, 120L, 5, List.of()),
                        Clock.fixed(Instant.parse("2026-09-11T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("이미 창이 찼으면 한 건도 더 발행하지 않는다")
    void doesNotExceedWindow() {
        when(candidateRepository.countByStatus(SEARCH_ID, CandidateStatus.DISPATCHED))
                .thenReturn((long) WINDOW);

        dispatcher.fillWindow(SEARCH_ID, snapshot());

        verify(candidateRepository, never()).findNextByStatus(any(), any(), anyInt());
        verify(launcher, never()).launchCandidate(any(), any());
    }

    @Test
    @DisplayName("빈자리만큼만 가져와 발행한다")
    void fillsOnlyTheRemainingRoom() {
        when(candidateRepository.countByStatus(SEARCH_ID, CandidateStatus.DISPATCHED))
                .thenReturn(1L);
        when(candidateRepository.findNextByStatus(SEARCH_ID, CandidateStatus.PENDING, WINDOW - 1))
                .thenReturn(pending(3));
        when(launcher.launchCandidate(any(), any())).thenReturn(100L, 101L, 102L);

        dispatcher.fillWindow(SEARCH_ID, snapshot());

        // 창이 4이고 1건이 실행 중이므로 3건만 나간다.
        verify(launcher, times(3)).launchCandidate(any(), any());
        verify(candidateRepository, times(3)).update(any());
    }

    @Test
    @DisplayName("발행에 실패한 후보는 PENDING으로 남는다 — 계산 실패와 발행 실패는 다르다")
    void publishFailureKeepsCandidatePending() {
        when(candidateRepository.countByStatus(SEARCH_ID, CandidateStatus.DISPATCHED))
                .thenReturn(0L);
        when(candidateRepository.findNextByStatus(SEARCH_ID, CandidateStatus.PENDING, WINDOW))
                .thenReturn(pending(1));
        when(launcher.launchCandidate(any(), any()))
                .thenThrow(new IllegalStateException("broker down"));

        dispatcher.fillWindow(SEARCH_ID, snapshot());

        // FAILED로 바꾸면 "이 조합은 계산할 수 없다"는 뜻이 되어 결과 해석과 재시도 셈이 틀어진다(FR-011).
        verify(candidateRepository, never()).update(any());
    }

    @Test
    @DisplayName("대기도 실행 중도 없으면 묶음이 끝난 것이다")
    void drainedWhenNothingLeft() {
        when(candidateRepository.countByStatus(SEARCH_ID, CandidateStatus.PENDING)).thenReturn(0L);
        when(candidateRepository.countByStatus(SEARCH_ID, CandidateStatus.DISPATCHED))
                .thenReturn(0L);

        assertThat(dispatcher.isDrained(SEARCH_ID)).isTrue();
    }

    private static List<SearchCandidate> pending(int count) {
        List<SearchCandidate> candidates = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            candidates.add(
                    SearchCandidate.pending(
                            i,
                            "STRATEGY_A",
                            ParameterCombination.of(Map.of("x", i)),
                            GridCoordinates.of(Map.of("x", i - 1))));
        }
        return candidates;
    }

    private static ParamSearchSnapshot snapshot() {
        return new ParamSearchSnapshot(
                SEARCH_ID,
                "PS-20260911-000001",
                null,
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
