package com.ssafy.thorp.backtest.domain.model;

/**
 * 실행이 지금 무엇을 하고 있는가(spec 004 Clarify Q4, research R-05). 상태 조회의 '진행 정도'에서 단계 이름으로 쓴다.
 *
 * <p>메시지 계약의 {@code application.port.message.ProcessingStage}와 짝이지만 값이 하나 더 많다. 워커는 자기가 아는 두
 * 단계(PREPARING_DATA·COMPUTING)만 보내고, {@link #SAVING}은 FINISHED를 받은 백엔드가 결과를 저장하는 동안 스스로 붙인다.
 */
public enum ProgressStage {
    /** 자료를 읽고 워밍업 구간을 자르는 중. STARTED 시점의 단계다. */
    PREPARING_DATA,
    /** 거래일을 순회하며 계산하는 중. 이 단계에서만 처리한 마지막 거래일과 비율이 의미를 가진다. */
    COMPUTING,
    /** 결과를 저장하는 중. 백엔드가 FINISHED를 받은 뒤 붙인다. */
    SAVING
}
