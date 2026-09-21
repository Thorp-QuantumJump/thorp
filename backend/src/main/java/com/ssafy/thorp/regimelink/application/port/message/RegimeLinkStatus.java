package com.ssafy.thorp.regimelink.application.port.message;

/**
 * {@code FINISHED} 종결 상태. 계산이 실패해도 이벤트는 정상 발행되므로 둘 다 워커가 끝까지 처리한 결과다.
 *
 * <p>링크가 {@code DATA_INSUFFICIENT}로 끝나는 경우는 여기 없다 — 자료 부족은 발행 전 백엔드 판정이라 작업 메시지가 나가지 않는다(research
 * R13).
 */
public enum RegimeLinkStatus {
    COMPLETED,
    FAILED
}
