package com.ssafy.thorp.news.application.repository;

import com.ssafy.thorp.news.domain.model.NewsPublisherPermissionHistory;
import java.util.List;

/**
 * 매체 허가 이력 저장소 경계(spec FR-015).
 *
 * <p>append-only다. 갱신·삭제 메서드를 두지 않아 이전 회신 근거가 사라지지 않게 한다.
 */
public interface NewsPublisherPermissionHistoryRepository {

    /**
     * 이력을 더하고 매체의 현재 허가 상태를 함께 갱신한다.
     *
     * <p>두 변경은 한 트랜잭션에서 이뤄져야 한다. 이력만 남고 상태가 안 바뀌면 공개 조회가 옛 상태로 계속 답한다.
     */
    NewsPublisherPermissionHistory appendAndApply(NewsPublisherPermissionHistory event);

    /** 한 매체의 이력을 발생 순서대로 읽는다. */
    List<NewsPublisherPermissionHistory> findByPublisherId(Long publisherId);

    /** 그 매체의 가장 최근 회신. 내보내기 허용 필드 투영에 쓴다(spec FR-017). */
    java.util.Optional<NewsPublisherPermissionHistory> findLatestResponse(Long publisherId);
}
