package com.ssafy.thorp.regimelink.application.port.message;

/**
 * 결과가 어떤 자료 위에서 계산됐는가. 010과 같이 <b>항상</b> {@code HISTORICAL_REVISION}이다 — 최신 개정자료로 과거를 다시 본 역사 비교이지
 * 당시 발표값 재현이 아니다(spec FR-015).
 */
public enum BasisKind {
    HISTORICAL_REVISION
}
