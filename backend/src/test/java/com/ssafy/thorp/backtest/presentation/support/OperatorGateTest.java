package com.ssafy.thorp.backtest.presentation.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** 운영자 문(T012). 열리는 조건은 하나 — 설정된 토큰과 헤더가 같다. 나머지는 모두 404 다. */
class OperatorGateTest {

    private static MockHttpServletRequest withHeader(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (value != null) {
            request.addHeader(OperatorGate.HEADER, value);
        }
        return request;
    }

    @Test
    @DisplayName("토큰이 설정되지 않은 환경에서는 어떤 헤더로도 열리지 않는다 — 운영에 값을 넣지 않으면 경로가 없다")
    void closedWhenNoTokenIsConfigured() {
        OperatorGate gate = new OperatorGate("");
        assertThatThrownBy(() -> gate.requireOperator(withHeader("anything")))
                .isInstanceOf(BusinessException.class)
                .extracting(exc -> ((BusinessException) exc).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("헤더가 없거나 틀리면 403 이 아니라 404 — 경로의 존재를 알려 주지 않는다")
    void wrongOrMissingHeaderIsNotFound() {
        OperatorGate gate = new OperatorGate("secret");
        assertThatThrownBy(() -> gate.requireOperator(withHeader(null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> gate.requireOperator(withHeader("secre")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> gate.requireOperator(withHeader("secret ")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("설정된 토큰과 같으면 통과한다")
    void matchingTokenPasses() {
        OperatorGate gate = new OperatorGate("secret");
        assertThatCode(() -> gate.requireOperator(withHeader("secret"))).doesNotThrowAnyException();
    }
}
