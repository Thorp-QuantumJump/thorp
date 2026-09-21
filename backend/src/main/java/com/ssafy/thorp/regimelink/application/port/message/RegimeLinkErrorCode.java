package com.ssafy.thorp.regimelink.application.port.message;

/**
 * {@code FINISHED(FAILED)}의 실패 사유(계약 §FINISHED.error). 네 값이 전부이며 워커는 {@code DATA_INSUFFICIENT}를 내지
 * 않는다.
 *
 * <p>계약 위반(JSON·스키마 오류)은 이 목록에 없다 — 이벤트가 아니라 reject → DLQ로 가고, 백엔드가 DLQ를 소비해 링크를 {@code
 * FAILED(SYSTEM_FAILURE)}로 끝낸다.
 */
public enum RegimeLinkErrorCode {
    /** 메시지의 입력 행·필드가 모자라다. 워커는 채우지 않는다 */
    INPUT_INCOMPLETE,
    /** 시장 자료 정합 검증 실패 */
    MARKET_DATA_MISSING,
    /** 계약은 맞지만 계산할 수 없는 조합 */
    INVALID_REQUEST,
    /** 그 밖의 결정적 오류 */
    ENGINE_ERROR
}
