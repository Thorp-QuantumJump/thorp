package com.ssafy.thorp.backtest.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BacktestJobTest {

    private static final BacktestPeriod PERIOD =
            new BacktestPeriod(LocalDate.of(2019, 1, 1), LocalDate.of(2023, 12, 31));
    private static final long INITIAL_CASH = 10_000_000L;

    @Test
    @DisplayName("create는 id 없이 QUEUED 상태로 만든다")
    void createStartsQueuedWithoutId() {
        BacktestJob job = BacktestJob.create("sma-cross", PERIOD, INITIAL_CASH);

        assertThat(job.getId()).isNull();
        assertThat(job.getStrategy()).isEqualTo("sma-cross");
        assertThat(job.getPeriod()).isEqualTo(PERIOD);
        assertThat(job.getInitialCash()).isEqualTo(INITIAL_CASH);
        assertThat(job.getStatus()).isEqualTo(BacktestStatus.QUEUED);
    }

    @Test
    @DisplayName("create는 빈 전략명을 거부한다")
    void createRejectsBlankStrategy() {
        assertThatThrownBy(() -> BacktestJob.create(" ", PERIOD, INITIAL_CASH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("create는 initialCash가 0 이하이면 거부한다")
    void createRejectsNonPositiveInitialCash() {
        assertThatThrownBy(() -> BacktestJob.create("sma-cross", PERIOD, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initialCash");
        assertThatThrownBy(() -> BacktestJob.create("sma-cross", PERIOD, -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("restore는 저장된 id·초기 자산·상태를 그대로 복원한다")
    void restoreKeepsIdAndStatus() {
        BacktestJob job =
                BacktestJob.restore(7L, "sma-cross", PERIOD, INITIAL_CASH, BacktestStatus.RUNNING);

        assertThat(job.getId()).isEqualTo(7L);
        assertThat(job.getInitialCash()).isEqualTo(INITIAL_CASH);
        assertThat(job.getStatus()).isEqualTo(BacktestStatus.RUNNING);
    }
}
