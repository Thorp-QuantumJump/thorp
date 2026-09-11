package com.ssafy.thorp.backtest.domain.model;

/**
 * 요청이 결과를 어떻게 얻었는가(spec 004 FR-003, Clarify Q2·Q3). 응답의 {@code resultSource}로 그대로 나간다.
 *
 * <p>세 값을 구분해 기록하는 이유는 "동일 버전 조회"와 "새 계산"을 화면에서 구분해 보여야 하고(FR-003), 재조회 비율이 실행 측정의 항목이기 때문이다(R-10).
 */
public enum ResultSource {
    /** 새 실행을 만들었다. 작업 메시지를 발행한 요청은 이것뿐이다. */
    NEW,
    /** 대기·실행 중인 동일 버전 실행에 합류했다. 새 계산을 만들지 않는다. */
    JOINED,
    /** 이미 완료된 동일 버전 실행의 결과를 재사용한다. 접수 즉시 결과를 볼 수 있다. */
    REUSED
}
