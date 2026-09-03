package com.thorp.api.backtest.domain.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BacktestPeriodTest {

    @Test
    @DisplayName("시작일이 종료일보다 늦으면 생성할 수 없다")
    void rejectsStartAfterEnd() {
        assertThatThrownBy(
                        () ->
                                new BacktestPeriod(
                                        LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("시작일과 종료일이 같은 하루 기간은 허용한다")
    void allowsSameDay() {
        LocalDate day = LocalDate.of(2024, 1, 1);
        BacktestPeriod period = new BacktestPeriod(day, day);
        assertThat(period.startDate()).isEqualTo(day);
        assertThat(period.endDate()).isEqualTo(day);
    }

    @Test
    @DisplayName("null 날짜는 허용하지 않는다")
    void rejectsNull() {
        assertThatThrownBy(() -> new BacktestPeriod(null, LocalDate.of(2024, 1, 1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BacktestPeriod(LocalDate.of(2024, 1, 1), null))
                .isInstanceOf(NullPointerException.class);
    }
}
