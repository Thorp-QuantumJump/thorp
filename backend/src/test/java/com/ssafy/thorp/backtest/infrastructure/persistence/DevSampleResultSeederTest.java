package com.ssafy.thorp.backtest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.backtest.application.repository.BacktestJobRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestResultRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import com.ssafy.thorp.backtest.domain.model.BacktestResult;
import com.ssafy.thorp.backtest.domain.model.BacktestStatus;
import com.ssafy.thorp.backtest.domain.vo.PerformanceSummary;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DevSampleResultSeederTest {

    @Mock private BacktestJobRepository jobRepository;
    @Mock private BacktestResultRepository resultRepository;
    @Mock private BacktestResultJpaRepository resultJpaRepository;
    @InjectMocks private DevSampleResultSeeder seeder;

    @Test
    @DisplayName("결과 행이 이미 있으면 아무것도 저장하지 않는다")
    void skipsWhenResultsExist() {
        when(resultJpaRepository.count()).thenReturn(1L);

        seeder.run(null);

        verify(jobRepository, never()).save(any());
        verify(resultRepository, never()).save(any());
    }

    @Test
    @DisplayName("비어 있으면 전략 3개의 COMPLETED Job과 FR-010 정합 결과를 저장한다")
    void seedsThreeStrategies() {
        when(resultJpaRepository.count()).thenReturn(0L);
        AtomicLong nextId = new AtomicLong(1);
        when(jobRepository.save(any(BacktestJob.class)))
                .thenAnswer(
                        invocation -> {
                            BacktestJob job = invocation.getArgument(0);
                            return BacktestJob.restore(
                                    nextId.getAndIncrement(),
                                    job.getStrategy(),
                                    job.getPeriod(),
                                    job.getInitialCash(),
                                    job.getStatus());
                        });
        when(resultRepository.save(any(BacktestResult.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        seeder.run(null);

        ArgumentCaptor<BacktestJob> jobCaptor = ArgumentCaptor.forClass(BacktestJob.class);
        verify(jobRepository, times(3)).save(jobCaptor.capture());
        assertThat(jobCaptor.getAllValues())
                .extracting(BacktestJob::getStrategy)
                .containsExactly("trend", "dualMomentum", "rsiMeanReversion");
        assertThat(jobCaptor.getAllValues())
                .allSatisfy(
                        job -> {
                            assertThat(job.getStatus()).isEqualTo(BacktestStatus.COMPLETED);
                            assertThat(job.getInitialCash()).isEqualTo(10_000_000L);
                        });

        ArgumentCaptor<BacktestResult> resultCaptor = ArgumentCaptor.forClass(BacktestResult.class);
        verify(resultRepository, times(3)).save(resultCaptor.capture());
        List<BacktestResult> results = resultCaptor.getAllValues();
        assertThat(results)
                .extracting(BacktestResult::getBacktestJobId)
                .containsExactly(1L, 2L, 3L);
        for (BacktestResult result : results) {
            PerformanceSummary summary = result.getSummary();
            long first = result.getEquityCurve().first().equity();
            long last = result.getEquityCurve().last().equity();
            assertThat(first).isEqualTo(10_000_000L);
            assertThat(summary.totalReturn()).isCloseTo((double) last / first - 1, within(1e-9));
            assertThat(summary.maxDrawdown()).isLessThanOrEqualTo(0.0);
            assertThat(result.getEquityCurve().size()).isEqualTo(20);
        }

        PerformanceSummary trend = results.get(0).getSummary();
        assertThat(trend.maxDrawdown()).isCloseTo(104.0 / 119 - 1, within(1e-9));
        assertThat(trend.maxDrawdownPeakDate()).isEqualTo(LocalDate.of(2020, 1, 1));
        assertThat(trend.maxDrawdownTroughDate()).isEqualTo(LocalDate.of(2020, 4, 1));
        assertThat(trend.maxDrawdownRecoveryDate()).isEqualTo(LocalDate.of(2020, 7, 1));

        PerformanceSummary rsi = results.get(2).getSummary();
        assertThat(rsi.maxDrawdown()).isCloseTo(139.0 / 151 - 1, within(1e-9));
        assertThat(rsi.maxDrawdownPeakDate()).isEqualTo(LocalDate.of(2021, 10, 1));
        assertThat(rsi.maxDrawdownTroughDate()).isEqualTo(LocalDate.of(2022, 4, 1));
        assertThat(rsi.maxDrawdownRecoveryDate()).isEqualTo(LocalDate.of(2022, 7, 1));
    }
}
