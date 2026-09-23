package com.ssafy.thorp.backtest.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 실행 기록에 남기는 규칙 이름표({@code 전략/버전})의 만들기와 풀기가 서로 되돌아가는지 본다(spec 004 T029). */
class RulesRefTest {

    @Test
    @DisplayName("기록한 표기를 다시 읽으면 같은 규칙이다")
    void roundTrips() {
        RulesRef ref = new RulesRef("TARGET_RISK_OVERLAY", "1");

        assertThat(ref.value()).isEqualTo("TARGET_RISK_OVERLAY/1");
        assertThat(RulesRef.parse(ref.value())).isEqualTo(ref);
    }

    @Test
    @DisplayName("버전 쪽에 구분자가 더 있어도 첫 구분자에서만 나눈다")
    void splitsOnTheFirstSeparatorOnly() {
        assertThat(RulesRef.parse("TARGET_RISK_OVERLAY/2/beta"))
                .isEqualTo(new RulesRef("TARGET_RISK_OVERLAY", "2/beta"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"TARGET_RISK_OVERLAY", "TARGET_RISK_OVERLAY/", "/1", ""})
    @DisplayName("전략/버전 표기가 아니면 지어내지 않고 거절한다")
    void rejectsMalformedValues(String value) {
        assertThatThrownBy(() -> RulesRef.parse(value))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("전략 이름에 구분자가 들어가면 다시 읽을 수 없으므로 만들지 않는다")
    void strategyIdMustNotContainTheSeparator() {
        assertThatThrownBy(() -> new RulesRef("A/B", "1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
