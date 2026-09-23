package com.ssafy.thorp.backtest.domain.model;

/**
 * CANCEL 제어 메시지를 보낸 이유(spec 004 R-19).
 *
 * <p>유예 시간 안에 FINISHED 가 오지 않아 실행을 강제 종결할 때 실패 사유가 이 값으로 갈린다. 종결 시점에 조건을 다시 계산해 추론하지 않는다 — 그때의 설정값이
 * 취소를 보낼 때와 다르면 사유가 뒤바뀐다.
 */
public enum CancelReason {
    /** 요청자가 눌렀다(R-04). 무응답이면 시스템 장애로 종결한다. */
    USER,
    /** 실행 시간 상한을 넘겼다. 무응답이면 한도 초과로 종결한다. */
    EXECUTION_LIMIT,
    /** 마지막 이벤트로부터 오래 조용하다(정체). 무응답이면 시스템 장애로 종결한다. */
    STALE;

    /** 이 이유로 보낸 CANCEL 이 무응답일 때 실행에 남길 실패 사유. */
    public String failureCode() {
        return this == EXECUTION_LIMIT ? "EXECUTION_LIMIT" : "SYSTEM_FAILURE";
    }
}
