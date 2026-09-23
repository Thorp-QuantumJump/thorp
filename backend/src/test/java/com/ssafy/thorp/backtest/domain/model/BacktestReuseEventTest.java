package com.ssafy.thorp.backtest.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 재사용·합류 기록(004 FR-008). 요청 참조는 만료 정리 뒤에만 비어 있을 수 있다(spec 005 T022). */
class BacktestReuseEventTest {

    private static final Instant AT = Instant.parse("2026-09-17T00:00:00Z");

    @Test
    @DisplayName("새 기록은 요청이 반드시 있다 — 접수가 판단한 순간에는 요청 행이 있다")
    void newEventRequiresRequest() {
        assertThatThrownBy(
                        () ->
                                BacktestReuseEvent.of(
                                        1L, null, ResultSource.JOINED, ExecutionType.SINGLE, AT))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("요청 참조가 끊긴 기록은 요청 없이 되살릴 수 있다")
    void detachedEventHasNoRequest() {
        BacktestReuseEvent detached =
                new BacktestReuseEvent(7L, 1L, null, ResultSource.REUSED, ExecutionType.SINGLE, AT);

        assertThat(detached.requestId()).isNull();
    }

    @Test
    @DisplayName("NEW 는 재사용이 아니라 기록하지 않는다")
    void rejectsNew() {
        assertThatThrownBy(
                        () ->
                                BacktestReuseEvent.of(
                                        1L, 2L, ResultSource.NEW, ExecutionType.SINGLE, AT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
