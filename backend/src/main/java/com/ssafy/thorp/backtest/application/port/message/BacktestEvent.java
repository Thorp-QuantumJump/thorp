package com.ssafy.thorp.backtest.application.port.message;

import java.time.Instant;

/**
 * 워커 → 백엔드 이벤트 envelope. {@code backtest.events} 큐로 들어온다. 계약 기준: README §4.2.
 *
 * <p>type별 payload: STARTED → {@link StartedPayload}, PROGRESS → {@link ProgressPayload}, FINISHED
 * → {@link FinishedPayload}. 수신 측(infrastructure)은 본문 {@code type}을 먼저 읽고 그에 맞는 {@code P}로 역직렬화한다.
 *
 * @param schemaVersion 현재 1
 * @param eventId 이벤트 고유 ID. 재발행 시 유지(중복 방지 키)
 * @param backtestId Job 식별자. {@code EngineInput.run.backtestId}와 같다
 * @param executionId 엔진 실행 시도 식별자. 재실행마다 새 값
 * @param sequence 실행 시도 내 순번. STARTED=0, 이후 1씩 증가
 * @param occurredAt UTC
 */
public record BacktestEvent<P>(
        int schemaVersion,
        String eventId,
        String backtestId,
        String executionId,
        BacktestEventType type,
        int sequence,
        Instant occurredAt,
        P payload) {}
