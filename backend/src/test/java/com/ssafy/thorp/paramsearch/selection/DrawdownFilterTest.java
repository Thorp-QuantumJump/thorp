package com.ssafy.thorp.paramsearch.selection;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.paramsearch.application.service.selection.DrawdownFilter;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 손실 제약 필터(T066, FR-013·FR-013d). */
class DrawdownFilterTest {

    private static final BigDecimal BASELINE = new BigDecimal("-0.2310");

    private final DrawdownFilter filter = new DrawdownFilter();

    @Test
    @DisplayName("기준보다 크게 줄어든 후보만 떨어진다")
    void onlyWorseThanBaselineFails() {
        assertThat(filter.passes(new BigDecimal("-0.1902"), BASELINE)).isTrue();
        assertThat(filter.passes(new BigDecimal("-0.3000"), BASELINE)).isFalse();
    }

    @Test
    @DisplayName("기준과 같으면 통과한다 — '보다 크지 않은'이다")
    void equalToBaselinePasses() {
        assertThat(filter.passes(new BigDecimal("-0.2310"), BASELINE)).isTrue();
    }

    @Test
    @DisplayName("부호 규약이 반대여도 폭으로 판정한다")
    void comparesMagnitudeNotSign() {
        // 엔진이 양수로 주는 규약으로 바뀌어도 "더 크게 줄었다"의 뜻은 절댓값에 있다.
        assertThat(filter.passes(new BigDecimal("0.1902"), new BigDecimal("0.2310"))).isTrue();
        assertThat(filter.passes(new BigDecimal("0.3000"), new BigDecimal("0.2310"))).isFalse();
    }

    @Test
    @DisplayName("비교할 값이 없는 후보는 통과시키지 않는다")
    void missingDrawdownDoesNotPass() {
        // 0으로 보정하면 "손실 없음"이 되어 최상위로 올라간다(FR-011).
        assertThat(filter.passes(null, BASELINE)).isFalse();
    }

    @Test
    @DisplayName("기준 전략 역할이 없거나 기준 실행이 실패하면 필터를 걸지 않는다")
    void notApplicableWithoutBaseline() {
        assertThat(filter.isApplicable(SelectionFixtures.ruleWithoutBaseline(), BASELINE))
                .as("등록표가 기준 전략을 지정하지 않았다(FR-013d)")
                .isFalse();
        assertThat(filter.isApplicable(SelectionFixtures.ruleWithBaseline(), null))
                .as("기준 실행이 실패해 비교할 값이 없다(Edge Case)")
                .isFalse();
        assertThat(filter.isApplicable(SelectionFixtures.ruleWithBaseline(), BASELINE)).isTrue();
    }
}
