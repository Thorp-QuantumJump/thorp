package com.ssafy.thorp.backtest.application.port.message;

import java.time.Instant;

/**
 * 백엔드 → 워커 제어 메시지. fanout exchange {@code backtest.control}로 모든 워커에 전달된다. 계약 기준: README §4.4, 픽스처
 * {@code control-cancel.json}.
 *
 * <p>워커는 자기 프로세스가 실행 중인 {@code runId}면 다음 거래일 처리 시점에 멈추고 FINISHED(CANCELLED)를 발행한다. 모르는 runId는
 * 무시한다.
 *
 * @param schemaVersion 현재 2
 */
public record ControlMessage(
        int schemaVersion, ControlType type, String runId, Instant requestedAt) {

    public enum ControlType {
        CANCEL
    }
}
