package com.ssafy.thorp.strategy.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.strategy.domain.vo.ConstraintOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 제약이 "두 파라미터 사이의 관계"라는 형태를 벗어나지 않는지 확인한다. */
class ParameterConstraintTest {

    @Test
    @DisplayName("제약은 자신이 참조하는 파라미터를 알려준다")
    void reportsReferencedParameters() {
        ParameterConstraint constraint =
                new ParameterConstraint(
                        "shortPeriod", ConstraintOperator.LT, "longPeriod", "짧아야 합니다.");

        assertThat(constraint.references("shortPeriod")).isTrue();
        assertThat(constraint.references("longPeriod")).isTrue();
        assertThat(constraint.references("allowShort")).isFalse();
    }

    @Test
    @DisplayName("상수는 제약의 변이 될 수 없다")
    void rejectsLiteralOperand() {
        assertThatThrownBy(
                        () ->
                                new ParameterConstraint(
                                        "shortPeriod", ConstraintOperator.LT, "200", "설명"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("파라미터 이름이어야 한다");
    }

    @Test
    @DisplayName("양변이 같은 파라미터인 제약은 거부한다")
    void rejectsSelfComparison() {
        assertThatThrownBy(
                        () ->
                                new ParameterConstraint(
                                        "shortPeriod", ConstraintOperator.LT, "shortPeriod", "설명"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("양변이 같은 파라미터");
    }

    @Test
    @DisplayName("위반 시 보여줄 설명이 없으면 거부한다")
    void requiresMessage() {
        assertThatThrownBy(
                        () ->
                                new ParameterConstraint(
                                        "shortPeriod", ConstraintOperator.LT, "longPeriod", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("설명");
    }
}
