package com.ssafy.thorp.paramsearch.application.port;

import java.math.BigDecimal;

/**
 * 후보 하나의 성과 숫자 셋(FR-018). 원본은 004의 {@code backtest_result}이고 007은 읽기만 한다.
 *
 * <p><b>왜 요약 전체가 아니라 셋인가</b>: 선정 계산과 후보 목록이 쓰는 것은 비용 차감 후 수익·가장 크게 줄었던 폭·거래비용뿐이다. 묶음 하나가 후보 수백 건이라
 * 후보마다 시계열·비중·예외가 딸린 요약을 읽으면 그만큼이 통째로 메모리에 올라온다(backend CLAUDE.md "읽지 않을 컬럼은 select에 넣지 않는다").
 *
 * <p><b>값이 없으면 {@code null}이고 0이 아니다</b>(FR-011, 원칙 VI). 실패·제외·미실행 후보는 애초에 이 객체를 갖지 않고, 완료됐는데 엔진이
 * 산출하지 못한 항목도 {@code null}로 남는다. 0으로 채우면 "손실 없음"·"비용 없음"으로 읽혀 낮은 수익과 구분되지 않는다.
 *
 * @param netReturn 비용 차감 후 수익
 * @param maxDrawdown 가장 크게 줄었던 폭. 엔진이 음수로 준다
 * @param tradingCost 거래비용 합계
 */
public record CandidatePerformance(
        BigDecimal netReturn, BigDecimal maxDrawdown, BigDecimal tradingCost) {

    /** 선정 계산에 쓸 수 있는가 — 정렬 지표와 하락 폭 필터가 둘 다 값을 요구한다. */
    public boolean isComparable() {
        return netReturn != null && maxDrawdown != null;
    }
}
