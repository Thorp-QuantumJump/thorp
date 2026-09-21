package com.ssafy.thorp.regimelink.application.port.message;

/**
 * 회전율의 연환산 단위. 구간 회전율만 연환산하고 수익률·최대 하락 폭은 연환산하지 않으므로, 값과 단위를 함께 실어 63거래일 값이 1년 값으로 읽히는 것을
 * 막는다(research R3).
 */
public enum TurnoverBasis {
    /** 252거래일 기준 연환산 */
    ANNUALIZED_252
}
