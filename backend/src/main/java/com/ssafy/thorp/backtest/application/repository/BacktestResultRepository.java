package com.ssafy.thorp.backtest.application.repository;

import com.ssafy.thorp.backtest.application.command.SaveBacktestResultCommand;

/** 백테스트 결과 저장소 포트. 구현은 {@code infrastructure.persistence}에 둔다. 조회는 결과 조회 API 작업에서 추가한다. */
public interface BacktestResultRepository {

    /**
     * 결과 행 1개와 거래 행 N개를 저장한다. 실패한 실행도 저장한다(오류 정보만 채워지고 성과·집계는 null).
     *
     * <p>같은 Job에 결과가 이미 있으면 PK 충돌로 실패한다. 호출자가 Job 상태(최종 여부)로 먼저 걸러낸다.
     */
    void save(SaveBacktestResultCommand command);
}
