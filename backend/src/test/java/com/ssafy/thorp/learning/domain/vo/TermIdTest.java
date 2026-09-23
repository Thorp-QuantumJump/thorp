package com.ssafy.thorp.learning.domain.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TermIdTest {

    @Test
    @DisplayName("2자와 64자는 경계 안이다")
    void acceptsBoundaryLengths() {
        assertThat(new TermId("ab").value()).isEqualTo("ab");
        String max = "a".repeat(64);
        assertThat(new TermId(max).value()).isEqualTo(max);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "Max-Drawdown", "max_drawdown", "max--drawdown", "-max", "max-"})
    @DisplayName("1자와 형식이 틀린 값을 거절한다")
    void rejectsInvalidFormats(String raw) {
        assertThatThrownBy(() -> new TermId(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("65자는 거절한다")
    void rejectsTooLong() {
        assertThatThrownBy(() -> new TermId("a".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
