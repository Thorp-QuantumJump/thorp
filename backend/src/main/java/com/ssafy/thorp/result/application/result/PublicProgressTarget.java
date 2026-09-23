package com.ssafy.thorp.result.application.result;

import com.ssafy.thorp.backtest.domain.model.RunStatus;

/**
 * 공개 토큰이 가리키는 진행 구독 대상.
 *
 * <p>진행은 <b>실행</b>에 붙고 토큰은 <b>요청</b>에 붙는다. 같은 조건의 요청 둘이 한 실행에 합류하면 서로 다른 토큰으로 같은 진행을 본다 — 그래서 구독 키는
 * 실행 행의 PK다.
 *
 * <p>{@code runId}는 응답에 실리지 않는다. 공개 경로는 실행 식별자를 내보내지 않는다(contracts/result-api.md "공개 응답에서 버리는 필드").
 *
 * @param status 구독을 걸 시점의 상태. 이미 종결이면 흘릴 것이 없으므로 부르는 쪽이 구독하지 않고 바로 닫는다
 */
public record PublicProgressTarget(long runId, RunStatus status) {}
