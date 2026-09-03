package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.port.BacktestJobPublisher;
import com.ssafy.thorp.backtest.application.repository.BacktestJobRepository;
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

    @Mock private BacktestJobRepository repository;
    @Mock private BacktestJobPublisher publisher;
    @InjectMocks private BacktestService service;

    @Test
    @DisplayName("create는 QUEUED Job을 저장한 뒤 저장된 Job을 발행하고 결과를 돌려준다")
    void createSavesThenPublishes() {
        BacktestJob saved =
                BacktestJob.restore(
                        1L, "sma-cross", new BacktestPeriod(START, END), BacktestStatus.QUEUED);
        when(repository.save(any(BacktestJob.class))).thenReturn(saved);

        BacktestJobResult result =
                service.create(new CreateBacktestCommand("sma-cross", START, END));

        ArgumentCaptor<BacktestJob> captor = ArgumentCaptor.forClass(BacktestJob.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getId()).isNull();
        assertThat(captor.getValue().getStatus()).isEqualTo(BacktestStatus.QUEUED);

        verify(publisher).publish(saved);
        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.strategy()).isEqualTo("sma-cross");
        assertThat(result.startDate()).isEqualTo(START);
        assertThat(result.endDate()).isEqualTo(END);
        assertThat(result.status()).isEqualTo(BacktestStatus.QUEUED);
    }

    @Test
    @DisplayName("기간이 잘못되면 저장·발행 없이 실패한다")
    void createRejectsInvalidPeriodBeforeSaving() {
        assertThatThrownBy(() -> service.create(new CreateBacktestCommand("sma-cross", END, START)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).save(any());
        verify(publisher, never()).publish(any());
    }

    @Test
    @DisplayName("get은 저장된 Job을 결과로 돌려준다")
    void getReturnsResult() {
        when(repository.findById(1L))
                .thenReturn(
                        Optional.of(
                                BacktestJob.restore(
                                        1L,
                                        "sma-cross",
                                        new BacktestPeriod(START, END),
                                        BacktestStatus.RUNNING)));

        BacktestJobResult result = service.get(1L);

        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.status()).isEqualTo(BacktestStatus.RUNNING);
    }

    @Test
    @DisplayName("get은 없는 id면 BacktestNotFoundException을 던진다")
    void getThrowsWhenMissing() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(999L)).isInstanceOf(BacktestNotFoundException.class);
    }
}
