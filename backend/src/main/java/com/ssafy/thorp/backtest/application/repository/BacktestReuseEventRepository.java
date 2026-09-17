package com.ssafy.thorp.backtest.application.repository;

import com.ssafy.thorp.backtest.domain.model.BacktestReuseEvent;

/**
 * 재사용·합류 기록 저장소 포트(FR-008). 추가만 한다 — 지우거나 고치는 경로를 두지 않는다.
 *
 * <p>조회는 두지 않았다. 이 기록을 읽는 곳은 T012의 집계 뷰뿐이고, 그 뷰는 SQL에서 직접 조인한다.
 *
 * <p><b>예외 하나: 만료된 비로그인 요청의 정리(spec 005 T022).</b> 기록이 요청 행을 외래 키로 가리키므로 요청을 지우려면 그 요청의 기록도 지워야 한다.
 * 고치는 경로는 여전히 없다.
 */
public interface BacktestReuseEventRepository {

    /** 기록을 남기고 id가 채워진 값을 돌려준다. */
    BacktestReuseEvent save(BacktestReuseEvent event);

    /**
     * 요청 하나에 붙은 기록을 지운다. 만료된 비로그인 요청을 정리할 때만 쓴다(spec 005 T022). 재사용 집계에서 그 요청의 몫이 빠진다.
     *
     * @return 지운 기록 수
     */
    int deleteByRequestId(long requestId);
}
