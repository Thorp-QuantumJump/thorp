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
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.vo.BacktestId;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
    private static final String BACKTEST_ID = "BT-20260907-000001";
    private static final BigDecimal CASH = new BigDecimal("10000000");

    @Mock private BacktestJobRepository repository;
    @Mock private BacktestJobPublisher publisher;
    @InjectMocks private BacktestService service;

    private static CreateBacktestCommand command(LocalDate start, LocalDate end) {
        return new CreateBacktestCommand(
                "MA_CROSSOVER",
                Map.of("shortWindow", 20),
                List.of("005930.XKRX"),
                start,
                end,
                CASH,
                PositionSizingMode.STRATEGY_DEFINED,
                new BigDecimal("0.50"),
                "069500.XKRX");
    }

    private static BacktestJob savedJob() {
        return BacktestJob.restore(
                1L,
                BacktestId.from(BACKTEST_ID),
                "MA_CROSSOVER",
                "1.0.0",
                Map.of("shortWindow", 20),
                List.of(new InstrumentId("005930.XKRX")),
                new BacktestPeriod(START, END),
                CASH,
                PositionSizingMode.STRATEGY_DEFINED,
                new BigDecimal("0.50"),
                new InstrumentId("069500.XKRX"),
                42,
                BacktestStatus.QUEUED);
    }

    @Test
    @DisplayName("create는 QUEUED Job을 저장한 뒤 저장된 Job을 발행하고 결과를 돌려준다")
    void createSavesThenPublishes() {
        BacktestJob saved = savedJob();
        when(repository.save(any(BacktestJob.class))).thenReturn(saved);

        BacktestJobResult result = service.create(command(START, END));

        ArgumentCaptor<BacktestJob> captor = ArgumentCaptor.forClass(BacktestJob.class);
        verify(repository).save(captor.capture());
        BacktestJob requested = captor.getValue();
        assertThat(requested.getId()).isNull();
        assertThat(requested.getBacktestId()).isNull();
        assertThat(requested.getStatus()).isEqualTo(BacktestStatus.QUEUED);
        assertThat(requested.getInstrumentIds()).containsExactly(new InstrumentId("005930.XKRX"));
        assertThat(requested.getBenchmarkInstrumentId()).isEqualTo(new InstrumentId("069500.XKRX"));

        verify(publisher).publish(saved);
        assertThat(result.backtestId()).isEqualTo(BACKTEST_ID);
        assertThat(result.status()).isEqualTo(BacktestStatus.QUEUED);
    }

    @Test
    @DisplayName("전략 버전과 시드는 요청이 아니라 고정 정책에서 온다")
    void createUsesExecutionPolicyDefaults() {
        when(repository.save(any(BacktestJob.class))).thenReturn(savedJob());

        service.create(command(START, END));

        ArgumentCaptor<BacktestJob> captor = ArgumentCaptor.forClass(BacktestJob.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStrategyVersion())
                .isEqualTo(ExecutionPolicy.STRATEGY_VERSION);
        assertThat(captor.getValue().getRandomSeed())
                .isEqualTo(ExecutionPolicy.DEFAULT_RANDOM_SEED);
    }

    @Test
    @DisplayName("기간이 잘못되면 저장·발행 없이 실패한다")
    void createRejectsInvalidPeriodBeforeSaving() {
        assertThatThrownBy(() -> service.create(command(END, START)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).save(any());
        verify(publisher, never()).publish(any());
    }

    @Test
    @DisplayName("get은 저장된 Job을 결과로 돌려준다")
    void getReturnsResult() {
        when(repository.findByBacktestId(BACKTEST_ID)).thenReturn(Optional.of(savedJob()));

        BacktestJobResult result = service.get(BACKTEST_ID);

        assertThat(result.backtestId()).isEqualTo(BACKTEST_ID);
        assertThat(result.status()).isEqualTo(BacktestStatus.QUEUED);
    }

    @Test
    @DisplayName("get은 없는 backtestId면 예외를 던진다")
    void getThrowsWhenMissing() {
        when(repository.findByBacktestId("BT-20260907-999999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("BT-20260907-999999"))
                .isInstanceOf(BacktestNotFoundException.class);
    }
}
