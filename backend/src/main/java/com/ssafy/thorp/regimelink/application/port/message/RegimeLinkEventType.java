package com.ssafy.thorp.regimelink.application.port.message;

/**
 * 워커 → 백엔드 이벤트 종류. 백테스트와 달리 진행률({@code PROGRESS})이 없다 — 63거래일은 한 번에 끝난다.
 *
 * <p>payload는 {@code STARTED} → {@link RegimeLinkStartedPayload}, {@code FINISHED} → {@link
 * RegimeLinkFinishedPayload}다.
 */
public enum RegimeLinkEventType {
    STARTED,
    FINISHED
}
