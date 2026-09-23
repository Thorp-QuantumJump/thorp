package com.ssafy.thorp.backtest.application.repository;

import com.ssafy.thorp.backtest.domain.model.BacktestReuseEvent;

/**
 * 재사용·합류 기록 저장소 포트(FR-008). 추가만 한다 — 지우는 경로를 두지 않는다.
 *
 * <p>조회는 두지 않았다. 이 기록을 읽는 곳은 T012의 집계 뷰뿐이고, 그 뷰는 SQL에서 직접 조인한다.
 *
 * <p><b>예외 하나: 만료된 비로그인 요청의 정리(spec 005 T022).</b> 기록이 요청 행을 외래 키로 가리키므로 요청을 지우기 전에 그 참조를 끊는다. 기록
 * 자체는 지우지 않는다 — 지우면 {@code reuse_count}가 정리할 때마다 줄어 동일 결과 재조회의 측정 근거(constitution 원칙 IX)가 사라진다.
 */
public interface BacktestReuseEventRepository {

    /** 기록을 남기고 id가 채워진 값을 돌려준다. */
    BacktestReuseEvent save(BacktestReuseEvent event);

    /**
     * 요청 하나에 붙은 기록의 요청 참조를 NULL로 끊는다. 만료된 비로그인 요청을 지우기 직전에만 쓴다(spec 005 T022). 기록은 남아 재사용 집계에 계속
     * 들어간다.
     *
     * @return 참조를 끊은 기록 수
     */
    int clearRequestId(long requestId);
}
