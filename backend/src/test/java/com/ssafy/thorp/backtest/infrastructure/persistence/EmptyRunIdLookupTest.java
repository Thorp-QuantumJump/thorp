package com.ssafy.thorp.backtest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 빈 id 목록으로 여러 건 조회를 부를 때의 규약.
 *
 * <p><b>왜 검사하는가</b>: 두 조회 모두 {@code WHERE ... IN (:runIds)} native 쿼리이고, PostgreSQL은 빈 {@code IN
 * ()}을 문법 오류로 거절한다. 그래서 호출자가 "물어볼 것이 없는" 상태로 부르면 예외가 난다 — 후보가 전부 제외·미실행이라 실행 행이 하나도 없는 묶음이 정확히 그
 * 상태다.
 *
 * <p>돌려주는 값이 빈 {@code Map}인 것만으로는 부족해 <b>저장소를 건드리지 않았다는 것까지</b> 본다. 빈 목록으로 쿼리를 돌리고 결과를 버려도 반환값은
 * 같지만, 그것은 묶음마다 헛 왕복을 한 번씩 만드는 것이다.
 *
 * <p>Testcontainers를 쓰지 않는다 — 여기서 확인할 것은 DB의 동작이 아니라 <b>DB에 가지 않는다</b>는 사실이다.
 */
class EmptyRunIdLookupTest {

    @Test
    @DisplayName("성과 조회: 빈 목록·null이면 쿼리 없이 빈 Map이다")
    void performanceLookupSkipsTheQueryWhenThereIsNothingToAsk() {
        BacktestResultJpaRepository resultRepository = mock(BacktestResultJpaRepository.class);
        BacktestResultRepositoryImpl repository =
                new BacktestResultRepositoryImpl(
                        mock(BacktestRunJpaRepository.class),
                        resultRepository,
                        mock(BacktestTradeJpaRepository.class));

        assertThat(repository.findPerformanceByRunIds(List.of())).isEmpty();
        assertThat(repository.findPerformanceByRunIds(Set.of())).isEmpty();
        assertThat(repository.findPerformanceByRunIds(null)).isEmpty();

        verifyNoInteractions(resultRepository);
    }

    @Test
    @DisplayName("실행 뷰 조회: 빈 목록·null이면 쿼리 없이 빈 Map이다")
    void runViewLookupSkipsTheQueryWhenThereIsNothingToAsk() {
        BacktestRunJpaRepository runRepository = mock(BacktestRunJpaRepository.class);
        BacktestRunRepositoryImpl repository =
                new BacktestRunRepositoryImpl(
                        runRepository, mock(EntityManager.class), Clock.systemUTC());

        assertThat(repository.findRunViews(List.of())).isEmpty();
        assertThat(repository.findRunViews(Set.of())).isEmpty();
        assertThat(repository.findRunViews(null)).isEmpty();

        verifyNoInteractions(runRepository);
    }
}
