package com.ssafy.thorp.strategy.application.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 허용 범위 판정 검증(T019, data-model §3·§4).
 *
 * <p>이 판정이 틀리면 화면이 허용 범위 밖 값을 기본값으로 보여 주거나, 자동 탐색이 사용자가 고를 수 없는 값을 훑는다. 로더가 기동 시 이 메서드로 등록표를 검사하므로
 * 여기서 새는 것은 기동 검사에서도 샌다.
 */
class AllowedRangeTest {

    @Nested
    @DisplayName("목록형")
    class Enumerated {

        private final AllowedRange range = AllowedRange.ofValues(List.of(21, 63, 126, 252));

        @Test
        @DisplayName("목록에 있는 값은 통과한다")
        void acceptsListedValue() {
            assertThat(range.contains(63)).isTrue();
        }

        @Test
        @DisplayName("같은 크기라도 형이 다르면 통과하지 않는다 — equals로만 판정한다")
        void rejectsEqualNumberOfAnotherType() {
            // 63과 63.0을 같은 축에 섞지 않기로 했으므로(내부 계약 §2.4) 형을 맞춰 주면 등록표에 없는 값을 통과시키는 셈이다.
            assertThat(range.contains(63.0)).isFalse();
            assertThat(range.contains(new BigDecimal("63"))).isFalse();
        }

        @Test
        @DisplayName("목록에 없는 값은 거절한다")
        void rejectsUnlistedValue() {
            assertThat(range.contains(64)).isFalse();
        }

        @Test
        @DisplayName("null은 통과하지 않는다")
        void rejectsNull() {
            assertThat(range.contains(null)).isFalse();
        }

        @Test
        @DisplayName("문자열 후보도 같은 규칙으로 판정한다 — 축의 의미를 보지 않는다")
        void treatsNonNumericValuesTheSameWay() {
            AllowedRange modes = AllowedRange.ofValues(List.of("TOP_N", "ALL"));

            assertThat(modes.contains("TOP_N")).isTrue();
            assertThat(modes.contains("THRESHOLD")).isFalse();
        }
    }

    @Nested
    @DisplayName("입력형")
    class Bounded {

        private final AllowedRange range = AllowedRange.between(10, 100, 5);

        @Test
        @DisplayName("범위 밖 값은 거절한다")
        void rejectsValueOutsideBounds() {
            assertThat(range.contains(5)).isFalse();
            assertThat(range.contains(105)).isFalse();
        }

        @Test
        @DisplayName("간격에 맞지 않는 값은 거절한다")
        void rejectsValueOffTheStep() {
            assertThat(range.contains(33)).isFalse();
        }

        @Test
        @DisplayName("범위 안이고 간격에 맞으면 통과한다")
        void acceptsValueOnTheStep() {
            assertThat(range.contains(35)).isTrue();
        }

        @Test
        @DisplayName("경계값도 통과한다")
        void acceptsBounds() {
            assertThat(range.contains(10)).isTrue();
            assertThat(range.contains(100)).isTrue();
        }

        @Test
        @DisplayName("음수에서 시작하는 범위도 간격을 시작값 기준으로 센다")
        void countsStepFromMinimum() {
            AllowedRange threshold = AllowedRange.between(-20, 50, 1);

            assertThat(threshold.contains(0)).isTrue();
            assertThat(threshold.contains(-21)).isFalse();
        }

        @Test
        @DisplayName("수치로 읽을 수 없는 값은 거절한다")
        void rejectsNonNumericValue() {
            assertThat(range.contains("사십")).isFalse();
        }
    }

    @Test
    @DisplayName("목록형과 입력형을 동시에 채우면 거절한다")
    void rejectsBothFormsAtOnce() {
        // 둘을 함께 채우면 화면이 목록을 그릴지 입력칸을 그릴지 정할 수 없다(data-model §4).
        assertThatThrownBy(
                        () ->
                                new AllowedRange(
                                        10,
                                        100,
                                        5,
                                        List.of(RegisteredChoice.of(21), RegisteredChoice.of(63))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("values");
    }

    @Test
    @DisplayName("문구를 담아도 값 판정과 값 목록은 달라지지 않는다 — 007은 문구를 보지 않는다")
    void labelsDoNotAffectValues() {
        AllowedRange labelled =
                AllowedRange.ofChoices(
                        List.of(
                                new RegisteredChoice("TOP_N", "상위 몇 개"),
                                new RegisteredChoice("ALL", "후보 전체")));

        assertThat(labelled.values()).containsExactly("TOP_N", "ALL");
        assertThat(labelled.contains("TOP_N")).isTrue();
        // 문구로는 고를 수 없다. 판정의 기준은 값 하나다.
        assertThat(labelled.contains("상위 몇 개")).isFalse();
    }

    @Test
    @DisplayName("범위를 적지 않은 축은 어떤 값도 통과시키지 않는다 — 없는 근거로 통과시키지 않는다")
    void rejectsEverythingWhenNothingIsDeclared() {
        assertThat(new AllowedRange(null, null, null, null).contains(1)).isFalse();
    }
}
