package com.thorp.api.backtest.application.repository;

import com.thorp.api.backtest.domain.model.BacktestJob;
import java.util.Optional;

/** 백테스트 Job 저장소 포트. 구현은 {@code infrastructure.persistence}에 둔다. */
public interface BacktestJobRepository {

    /** 저장 후 id가 채워진 Job을 돌려준다. */
    BacktestJob save(BacktestJob job);

    Optional<BacktestJob> findById(Long id);
}
