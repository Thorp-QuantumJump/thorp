package com.ssafy.thorp.backtest.application.repository;

import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import java.util.Optional;

/** 백테스트 Job 저장소 포트. 구현은 {@code infrastructure.persistence}에 둔다. */
public interface BacktestRunRepository {

    /**
     * 신규 Job 생성 전용. 저장하면서 외부 식별자 {@code backtestId}를 부여하고, id와 backtestId가 채워진 Job을 돌려준다.
     *
     * <p>id가 이미 있는 Job이면 {@link IllegalArgumentException}. 상태 변경은 {@link #update(BacktestRun)}.
     */
    BacktestRun save(BacktestRun job);

    /**
     * 저장된 Job의 상태 전이를 반영한다. 도메인에서 바뀔 수 있는 값은 실행 상태({@code RunState})뿐이므로 그것만 갱신하고, 갱신된 Job을 돌려준다.
     *
     * <p>id가 없는 Job이거나 저장소에 없는 id면 {@link IllegalArgumentException}.
     */
    BacktestRun update(BacktestRun job);

    Optional<BacktestRun> findById(Long id);

    Optional<BacktestRun> findByBacktestId(String backtestId);
}
