package com.ssafy.thorp.backtest.application.repository;

import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import java.util.List;
import java.util.Optional;

/**
 * 분석 요청 저장소 포트(spec 004 research R-02). 구현은 {@code infrastructure.persistence}에 둔다.
 *
 * <p>접수 유스케이스(T005)가 쓰기 시작한다. T003은 스키마·모델·저장소만 둔다.
 */
public interface BacktestRequestRepository {

    /** 신규 요청 생성 전용. 저장하면서 외부 식별자 {@code backtestId}를 부여한다. */
    BacktestRequest save(BacktestRequest request);

    /** 취소 기록을 반영한다. 도메인에서 바뀔 수 있는 값은 취소 관련 세 개뿐이다. */
    BacktestRequest update(BacktestRequest request);

    Optional<BacktestRequest> findByBacktestId(String backtestId);

    /**
     * 요청 행을 잠그고 <b>그 시점의 값을 읽는다</b>(SELECT … FOR UPDATE). 같은 요청을 동시에 취소하면 뒤엣것이 잠금을 얻은 뒤 앞엣것이 남긴 취소
     * 상태를 보아야 한다 — 잠금 전에 읽어 두면 옛 상태로 "아직 취소되지 않았다"고 판단해 남은 요청이 있는데도 실행을 멈춘다(R-04).
     */
    Optional<BacktestRequest> findByBacktestIdForUpdate(String backtestId);

    /** 한 실행에 붙은 요청 전부. 마지막 활성 요청인지 판단할 때 쓴다(R-04). */
    List<BacktestRequest> findByRunId(Long runId);

    /** 취소되지 않은 요청 수. 0이면 이 실행을 멈춰도 된다(R-04). */
    long countActiveByRunId(Long runId);
}
