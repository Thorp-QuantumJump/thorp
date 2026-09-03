package com.ssafy.thorp.backtest.application.repository;

import com.ssafy.thorp.backtest.domain.model.BacktestResult;
import java.util.Optional;

/**
 * 백테스트 결과 저장소 포트. 구현은 {@code infrastructure.persistence}에 둔다.
 *
 * <p>결과 행은 Worker가 쓰는 것이 원칙이며 백엔드는 조회한다. {@code save}는 dev 시더와 향후 백엔드 경유 저장에 쓴다.
 */
public interface BacktestResultRepository {

    /** 저장 후 id가 채워진 결과를 돌려준다. */
    BacktestResult save(BacktestResult result);

    Optional<BacktestResult> findByBacktestJobId(Long backtestJobId);
}
