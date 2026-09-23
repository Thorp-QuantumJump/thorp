package com.ssafy.thorp.backtest.domain.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InstrumentIdTest {

    @Test
    @DisplayName("6자리 숫자 종목코드에 .XKRX가 붙은 값을 허용한다")
    void acceptsKrxFormat() {
        assertThat(new InstrumentId("005930.XKRX").value()).isEqualTo("005930.XKRX");
    }

    @Test
    @DisplayName("영문 대문자가 섞인 6글자 단축코드도 허용한다")
    void acceptsAlphanumericShortCode() {
        // 2026-09-11 실측에서 ETF 1,168종 중 304종(26%)이 이런 코드였다 (PD-01, spec 001 T031).
        assertThat(new InstrumentId("0111J0.XKRX").value()).isEqualTo("0111J0.XKRX");
        assertThat(new InstrumentId("0182R0.XKRX").value()).isEqualTo("0182R0.XKRX");
        assertThat(new InstrumentId("0005D0.XKRX").value()).isEqualTo("0005D0.XKRX");
    }

    @Test
    @DisplayName("KRX 형식이 아니면 거부한다")
    void rejectsOtherFormats() {
        assertThatThrownBy(() -> new InstrumentId("005930"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InstrumentId("5930.XKRX"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InstrumentId("AAPL.XNAS"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InstrumentId("005930.xkrx"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InstrumentId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("자리 수가 어긋나거나 소문자가 섞인 단축코드는 거부한다")
    void rejectsShortCodesOutsideTheWidenedFormat() {
        // 소문자를 받으면 같은 종목이 0111J0과 0111j0 두 값으로 저장될 수 있다.
        assertThatThrownBy(() -> new InstrumentId("0111j0.XKRX"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InstrumentId("0111J.XKRX"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InstrumentId("0111J00.XKRX"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InstrumentId("0111-0.XKRX"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
