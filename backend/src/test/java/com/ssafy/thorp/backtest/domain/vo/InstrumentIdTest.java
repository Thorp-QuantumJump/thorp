package com.ssafy.thorp.backtest.domain.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InstrumentIdTest {

    @Test
    @DisplayName("6자리 종목코드에 .XKRX가 붙은 값을 허용한다")
    void acceptsKrxFormat() {
        assertThat(new InstrumentId("005930.XKRX").value()).isEqualTo("005930.XKRX");
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
}
