package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.backtest.BacktestResultFixtures;
import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.port.BacktestJobPublisher;
import com.ssafy.thorp.backtest.application.repository.BacktestJobRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestResultRepository;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.domain.exception.BacktestNotFoundException;
import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import com.ssafy.thorp.backtest.domain.model.BacktestStatus;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BacktestServiceTest {

    private static final LocalDate START = LocalDate.of(2019, 1, 1);
    private static final LocalDate END = LocalDate.of(2023, 12, 31);
    private static final long INITIAL_CASH = 10_000_000L;

    @Mock private BacktestJobRepository repository;
    @Mock private BacktestResultRepository resultRepository;
    @Mock private BacktestJobPublisher publisher;
    @InjectMocks private BacktestService service;

    @Test
    @DisplayName("create는 QUEUED Job을 저장한 뒤 저장된 Job을 발행하고 config가 담긴 결과를 돌려준다")
    void createSavesThenPublishes() {
        BacktestJob saved = job(1L, BacktestStatus.QUEUED);
        when(repository.save(any(BacktestJob.class))).thenReturn(saved);

        BacktestJobResult result =
                service.create(new CreateBacktestCommand("sma-cross", START, END, INITIAL_CASH));

        ArgumentCaptor<BacktestJob> captor = ArgumentCaptor.forClass(BacktestJob.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getId()).isNull();
        assertThat(captor.getValue().getInitialCash()).isEqualTo(INITIAL_CASH);
        assertThat(captor.getValue().getStatus()).isEqualTo(BacktestStatus.QUEUED);

        verify(publisher).publish(saved);
        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.status()).isEqualTo(BacktestStatus.QUEUED);
        assertThat(result.config().strategy()).isEqualTo("sma-cross");
        assertThat(result.config().startDate()).isEqualTo(START);
        assertThat(result.config().endDate()).isEqualTo(END);
        assertThat(result.config().initialCash()).isEqualTo(INITIAL_CASH);
        assertThat(result.summary()).isNull();
    }

    @Test
    @DisplayName("기간이 잘못되면 저장·발행 없이 실패한다")
    void createRejectsInvalidPeriodBeforeSaving() {
        assertThatThrownBy(
                        () ->
                                service.create(
                                        new CreateBacktestCommand(
                                                "sma-cross", END, START, INITIAL_CASH)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).save(any());
        verify(publisher, never()).publish(any());
    }

    @Test
    @DisplayName("initialCash가 0 이하이면 저장·발행 없이 실패한다")
    void createRejectsNonPositiveInitialCashBeforeSaving() {
        assertThatThrownBy(
                        () ->
                                service.create(
                                        new CreateBacktestCommand("sma-cross", START, END, 0L)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).save(any());
        verify(publisher, never()).publish(any());
    }

    @Test
    @DisplayName("get은 COMPLETED가 아니면 결과 저장소를 조회하지 않고 summary null로 돌려준다")
    void getReturnsWithoutSummaryWhenNotCompleted() {
        when(repository.findById(1L)).thenReturn(Optional.of(job(1L, BacktestStatus.RUNNING)));

        BacktestJobResult result = service.get(1L);

        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.status()).isEqualTo(BacktestStatus.RUNNING);
        assertThat(result.config().initialCash()).isEqualTo(INITIAL_CASH);
        assertThat(result.summary()).isNull();
        verify(resultRepository, never()).findByBacktestJobId(any());
    }

    @Test
    @DisplayName("get은 COMPLETED Job이면 저장된 결과의 summary를 함께 돌려준다")
    void getReturnsSummaryWhenCompleted() {
        when(repository.findById(1L)).thenReturn(Optional.of(job(1L, BacktestStatus.COMPLETED)));
        when(resultRepository.findByBacktestJobId(1L))
                .thenReturn(Optional.of(BacktestResultFixtures.sampleResult(1L)));

        BacktestJobResult result = service.get(1L);

        assertThat(result.status()).isEqualTo(BacktestStatus.COMPLETED);
        assertThat(result.summary()).isNotNull();
        assertThat(result.summary().initialEquity())
                .isEqualTo(BacktestResultFixtures.INITIAL_EQUITY);
        assertThat(result.summary().finalEquity()).isEqualTo(BacktestResultFixtures.FINAL_EQUITY);
        assertThat(result.summary().totalReturn()).isEqualTo(1.248);
        assertThat(result.summary().maxDrawdownPeakDate()).isEqualTo(LocalDate.of(2020, 1, 1));
        assertThat(result.summary().winRate()).isNull();
        assertThat(result.summary().metricDefinitionVersion()).isEqualTo("1.0");
    }

    @Test
    @DisplayName("get은 COMPLETED이지만 결과 행이 없으면 summary null로 돌려준다")
    void getReturnsWithoutSummaryWhenResultMissing() {
        when(repository.findById(1L)).thenReturn(Optional.of(job(1L, BacktestStatus.COMPLETED)));
        when(resultRepository.findByBacktestJobId(1L)).thenReturn(Optional.empty());

        BacktestJobResult result = service.get(1L);

        assertThat(result.status()).isEqualTo(BacktestStatus.COMPLETED);
        assertThat(result.summary()).isNull();
    }

    @Test
    @DisplayName("get은 없는 id면 BacktestNotFoundException을 던진다")
    void getThrowsWhenMissing() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(999L)).isInstanceOf(BacktestNotFoundException.class);
        verify(resultRepository, never()).findByBacktestJobId(any());
    }

    private static BacktestJob job(Long id, BacktestStatus status) {
        return BacktestJob.restore(
                id, "sma-cross", new BacktestPeriod(START, END), INITIAL_CASH, status);
    }
}
