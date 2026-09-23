package com.ssafy.thorp.backtest.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 결과 공개 토큰의 형식(spec 005 FR-019). 주소에 그대로 실리므로 형식이 곧 계약이다. */
class PublicResultTokenTest {

    @Test
    @DisplayName("발급한 토큰은 소문자 16진 32자(128비트)다")
    void generatedTokenIsLowerHex32() {
        PublicResultToken token = PublicResultToken.generate();

        assertThat(token.value()).matches("^[0-9a-f]{32}$");
        assertThat(token.toString()).isEqualTo(token.value());
    }

    @Test
    @DisplayName("발급할 때마다 다른 값이다")
    void generatedTokensDiffer() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            assertThat(seen.add(PublicResultToken.generate().value())).isTrue();
        }
    }

    @Test
    @DisplayName("같은 값으로 되살린 토큰은 같다")
    void restoredTokenEqualsOriginal() {
        PublicResultToken token = PublicResultToken.generate();

        assertThat(PublicResultToken.of(token.value())).isEqualTo(token).hasSameHashCodeAs(token);
    }

    /** 대문자를 소문자로 고쳐 받으면 한 결과에 주소가 둘 생긴다. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "0123456789ABCDEF0123456789ABCDEF",
                "0123456789abcdef0123456789abcde",
                "0123456789abcdef0123456789abcdef0",
                "0123456789abcdef0123456789abcdeg",
                ""
            })
    @DisplayName("형식이 틀리면(대문자·31자·33자·16진 아님·빈 값) 되살리지 않는다")
    void rejectsMalformedValues(String value) {
        assertThatThrownBy(() -> PublicResultToken.of(value))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null 은 받지 않는다")
    void rejectsNull() {
        assertThatThrownBy(() -> PublicResultToken.of(null))
                .isInstanceOf(NullPointerException.class);
    }
}
