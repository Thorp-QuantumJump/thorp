package com.ssafy.thorp.backtest.application.repository;

import com.ssafy.thorp.backtest.domain.model.BacktestReuseEvent;

/**
 * 재사용·합류 기록 저장소 포트(FR-008). 추가만 한다 — 지우거나 고치는 경로를 두지 않는다.
 *
 * <p>조회는 두지 않았다. 이 기록을 읽는 곳은 T012의 집계 뷰뿐이고, 그 뷰는 SQL에서 직접 조인한다.
 */
public interface BacktestReuseEventRepository {

    /** 기록을 남기고 id가 채워진 값을 돌려준다. */
    BacktestReuseEvent save(BacktestReuseEvent event);
}
