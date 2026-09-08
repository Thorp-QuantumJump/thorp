package com.ssafy.thorp.backtest.application.repository;

import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import java.util.Optional;

/** 백테스트 Job 저장소 포트. 구현은 {@code infrastructure.persistence}에 둔다. */
public interface BacktestJobRepository {

    /**
     * 신규 Job 생성 전용. 저장하면서 외부 식별자 {@code backtestId}를 부여하고, id와 backtestId가 채워진 Job을 돌려준다.
     *
     * <p>id가 이미 있는 Job이면 {@link IllegalArgumentException}. 상태 변경은 별도 메서드로 추가한다.
     */
    BacktestJob save(BacktestJob job);

    Optional<BacktestJob> findById(Long id);

    Optional<BacktestJob> findByBacktestId(String backtestId);
}
