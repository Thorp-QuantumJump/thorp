package com.ssafy.thorp.learning.domain.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ArticleIdTest {

    @Test
    @DisplayName("3자와 64자는 경계 안이다")
    void acceptsBoundaryLengths() {
        assertThat(new ArticleId("abc").value()).isEqualTo("abc");
        String max = "a".repeat(64);
        assertThat(new ArticleId(max).value()).isEqualTo(max);
    }

    @Test
    @DisplayName("하이픈으로 이은 소문자·숫자 조각을 받는다")
    void acceptsHyphenatedSegments() {
        assertThat(new ArticleId("returns-and-losses").value()).isEqualTo("returns-and-losses");
        assertThat(new ArticleId("step-2").value()).isEqualTo("step-2");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ab",
                "Bad_Key",
                "Upper",
                "under_score",
                "double--hyphen",
                "-leading",
                "trailing-",
                "with space",
                ""
            })
    @DisplayName("길이·대문자·밑줄·연속 하이픈·앞뒤 하이픈을 거절한다")
    void rejectsInvalidFormats(String raw) {
        assertThatThrownBy(() -> new ArticleId(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("65자는 거절한다")
    void rejectsTooLong() {
        assertThatThrownBy(() -> new ArticleId("a".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
