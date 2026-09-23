package com.ssafy.thorp.backtest.domain.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BacktestIdTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 7);

    @Test
    @DisplayName("날짜와 순번으로 BT-yyyyMMdd-000001 형식을 만든다")
    void formatsWithSixDigits() {
        assertThat(BacktestId.of(DATE, 1).value()).isEqualTo("BT-20260907-000001");
    }

    @Test
    @DisplayName("순번이 999,999를 넘으면 자릿수가 늘어난다")
    void growsBeyondSixDigits() {
        assertThat(BacktestId.of(DATE, 1_000_000).value()).isEqualTo("BT-20260907-1000000");
    }

    @Test
    @DisplayName("형식에 맞는 문자열은 복원한다")
    void restoresValidValue() {
        assertThat(BacktestId.from("BT-20260907-000123").value()).isEqualTo("BT-20260907-000123");
    }

    @Test
    @DisplayName("형식에 맞지 않는 문자열은 거부한다")
    void rejectsInvalidValue() {
        assertThatThrownBy(() -> BacktestId.from("BT-20260907-00012"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BacktestId.from("20260907-000123"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BacktestId.from("BT-2026-09-07-000123"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BacktestId.from(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("음수 순번은 형식 검증에서 걸린다")
    void rejectsNegativeSequence() {
        assertThatThrownBy(() -> BacktestId.of(DATE, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
