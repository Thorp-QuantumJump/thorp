package com.ssafy.thorp.regimelink.application.port.message;

import java.time.Instant;

/**
 * 워커 → 백엔드 이벤트 envelope(schemaVersion 1). {@code regime-link.events} 큐로 들어온다.
 *
 * <p>계약 기준: {@code docs/specs/regime-link-messaging/README.md} "이벤트 봉투", 픽스처 {@code event-*.json}.
 * 작업과 달리 {@code schemaVersion}이 본문 첫 필드에 있다.
 *
 * <p>type별 payload: {@code STARTED} → {@link RegimeLinkStartedPayload}, {@code FINISHED} → {@link
 * RegimeLinkFinishedPayload}. 수신 측(infrastructure)은 본문 {@code type}을 먼저 읽고 그에 맞는 {@code P}로 역직렬화한다.
 *
 * @param eventId 이벤트 고유 ID. 재발행해도 유지한다(멱등 키)
 * @param linkId 연결 식별자. 작업의 {@code job.linkId}와 같다
 * @param attempt 이 이벤트가 속한 발행 시도 번호. 작업의 {@code job.attempt}와 같다
 * @param occurredAt UTC
 */
public record RegimeLinkEvent<P>(
        int schemaVersion,
        String eventId,
        String linkId,
        int attempt,
        RegimeLinkEventType type,
        Instant occurredAt,
        P payload) {}
