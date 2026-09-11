package com.ssafy.thorp.news.application.repository;

import com.ssafy.thorp.news.domain.model.NewsCollectionState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 수집 상태 저장소 경계.
 *
 * <p>수집 위치는 기사 저장이 확정된 트랜잭션 안에서만 전진한다. 실패·부분 수집에서 위치가 밀리면 그 구간의 기사가 영구히 누락된다(spec FR-022).
 */
public interface NewsCollectionStateRepository {

    NewsCollectionState save(NewsCollectionState state);

    Optional<NewsCollectionState> findBySourceKey(String sourceKey);

    /** 지금 실행할 수 있는 뉴스원. 점유가 없거나 만료됐고 다음 시도 시각이 지난 것만 고른다. */
    List<NewsCollectionState> findDue(Instant now);

    /**
     * 응답의 마지막 갱신 시각(spec FR-014).
     *
     * <p>활성화되고 현재 노출 가능한 뉴스원들의 마지막 성공 시각 중 가장 최근 값이다. 성공 이력이 없으면 빈 값이며, 화면은 '아직 수집되지 않음'으로 표시한다.
     */
    Optional<Instant> findLatestSuccessAt(boolean publicOnly);
}
