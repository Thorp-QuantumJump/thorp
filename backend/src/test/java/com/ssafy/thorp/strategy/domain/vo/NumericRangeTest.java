package com.ssafy.thorp.strategy.domain.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NumericRangeTest {

    @Test
    @DisplayName("경계를 포함하는 범위는 경계값을 받아들인다")
    void inclusiveBoundsAcceptEdges() {
        NumericRange range = NumericRange.closed(new BigDecimal("2"), new BigDecimal("120"));

        assertThat(range.contains(new BigDecimal("2"))).isTrue();
        assertThat(range.contains(new BigDecimal("120"))).isTrue();
        assertThat(range.contains(new BigDecimal("1"))).isFalse();
        assertThat(range.contains(new BigDecimal("121"))).isFalse();
    }

    @Test
    @DisplayName("배타 하한은 경계값 자체를 거부한다")
    void exclusiveLowerBoundRejectsEdge() {
        NumericRange range =
                new NumericRange(new BigDecimal("50"), false, new BigDecimal("95"), true, null);

        assertThat(range.contains(new BigDecimal("50"))).isFalse();
        assertThat(range.contains(new BigDecimal("50.1"))).isTrue();
        assertThat(range.contains(new BigDecimal("95"))).isTrue();
    }

    @Test
    @DisplayName("한쪽 경계만 지정한 열린 범위도 성립한다")
    void openUpperBoundAcceptsLargeValues() {
        NumericRange range = NumericRange.atLeast(BigDecimal.ONE);

        assertThat(range.hasMin()).isTrue();
        assertThat(range.hasMax()).isFalse();
        assertThat(range.contains(new BigDecimal("99999"))).isTrue();
        assertThat(range.contains(BigDecimal.ZERO)).isFalse();
    }

    @Test
    @DisplayName("허용 간격은 하한을 기준으로 판정한다")
    void stepIsAnchoredAtMin() {
        NumericRange range =
                NumericRange.closed(
                        new BigDecimal("5"), new BigDecimal("50"), new BigDecimal("0.5"));

        // (30.5 - 5) / 0.5 = 51 -> 배수
        assertThat(range.matchesStep(new BigDecimal("30.5"))).isTrue();
        // (30.2 - 5) / 0.5 = 50.4 -> 배수 아님
        assertThat(range.matchesStep(new BigDecimal("30.2"))).isFalse();
    }

    @Test
    @DisplayName("십진 연산으로 판정하므로 부동소수 오차가 결과를 흔들지 않는다")
    void stepUsesDecimalArithmetic() {
        NumericRange range =
                NumericRange.closed(BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal("0.1"));

        // double 로 계산하면 0.1 + 0.2 != 0.3 이라 실패하는 값들
        assertThat(range.matchesStep(new BigDecimal("0.3"))).isTrue();
        assertThat(range.matchesStep(new BigDecimal("0.7"))).isTrue();
        assertThat(range.matchesStep(new BigDecimal("0.75"))).isFalse();
    }

    @Test
    @DisplayName("허용 간격이 없으면 간격 제약이 없다")
    void withoutStepEverythingMatches() {
        NumericRange range = NumericRange.closed(BigDecimal.ZERO, new BigDecimal("100"));

        assertThat(range.hasStep()).isFalse();
        assertThat(range.matchesStep(new BigDecimal("0.0001"))).isTrue();
    }

    @Test
    @DisplayName("허용 간격은 0보다 커야 한다")
    void rejectsNonPositiveStep() {
        assertThatThrownBy(
                        () ->
                                new NumericRange(
                                        BigDecimal.ZERO,
                                        true,
                                        BigDecimal.TEN,
                                        true,
                                        BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("step");
    }

    @Test
    @DisplayName("기준점 없이 허용 간격만 지정할 수 없다")
    void rejectsStepWithoutMin() {
        assertThatThrownBy(() -> new NumericRange(null, true, BigDecimal.TEN, true, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min");
    }
}
