package com.ssafy.thorp.backtest.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RunStatusTest {

    @ParameterizedTest
    @EnumSource(
            value = RunStatus.class,
            names = {"COMPLETED", "FAILED", "CANCELLED"})
    @DisplayName("종결된 셋은 최종 상태다 — 이 상태의 실행에 온 이벤트는 무시된다")
    void terminalStatusesAreFinal(RunStatus status) {
        assertThat(status.isFinal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(
            value = RunStatus.class,
            names = {"QUEUED", "RUNNING"})
    @DisplayName("대기·실행 중은 최종 상태가 아니다")
    void inFlightStatusesAreNotFinal(RunStatus status) {
        assertThat(status.isFinal()).isFalse();
    }

    @Test
    @DisplayName("CANCELLED가 최종 상태 집합에 들어 있다 — 취소도 종결이다(Clarify Q1)")
    void cancelledIsTerminal() {
        assertThat(RunStatus.CANCELLED.isFinal()).isTrue();
        assertThat(RunStatus.values()).hasSize(5);
    }
}
