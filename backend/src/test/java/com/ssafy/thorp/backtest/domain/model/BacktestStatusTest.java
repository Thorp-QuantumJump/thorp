package com.ssafy.thorp.backtest.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BacktestStatusTest {

    @Test
    @DisplayName("COMPLETED와 FAILED만 최종 상태다")
    void onlyCompletedAndFailedAreFinal() {
        assertThat(BacktestStatus.QUEUED.isFinal()).isFalse();
        assertThat(BacktestStatus.RUNNING.isFinal()).isFalse();
        assertThat(BacktestStatus.COMPLETED.isFinal()).isTrue();
        assertThat(BacktestStatus.FAILED.isFinal()).isTrue();
    }
}
