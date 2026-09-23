package com.ssafy.thorp.backtest.application.result;

import com.ssafy.thorp.backtest.domain.model.RunStatus;

/**
 * 실행 하나를 <b>목록에서</b> 가리키는 데 필요한 값만 모은 뷰.
 *
 * <p>여러 실행을 한 번에 훑는 화면(자동 탐색 후보 목록)이 쓴다. 실행마다 상세 조회를 돌리면 왕복이 실행 수만큼 늘고, 쓰지도 않을 시계열·비중·예외가 함께 온다.
 *
 * <p>{@code backtestId}가 <b>요청</b>의 식별자인 이유: 사용자가 {@code GET /backtests/{id}}로 여는 값이 그것이다. 실행 행도 자기
 * {@code backtest_id}를 갖지만 그 값으로는 상세를 열 수 없다(요청:실행 = N:1, spec 004 R-02). 한 실행에 요청이 여럿이면 가장 먼저 접수된
 * 것을 준다 — 어느 요청으로 열어도 같은 결과를 보므로 사용자에게 차이가 없고, 가장 이른 것이 재현 가능한 선택이다.
 *
 * @param performance 결과 행이 없으면 null. 실행 중이거나 실패한 실행이다
 */
public record RunView(String backtestId, RunStatus status, ResultPerformance performance) {}
