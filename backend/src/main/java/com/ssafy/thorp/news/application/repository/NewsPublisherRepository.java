package com.ssafy.thorp.news.application.repository;

import com.ssafy.thorp.news.domain.model.NewsPublisher;
import java.util.List;
import java.util.Optional;

/** 매체 저장소 경계. 구현은 infrastructure에 둔다. */
public interface NewsPublisherRepository {

    NewsPublisher save(NewsPublisher publisher);

    Optional<NewsPublisher> findById(Long id);

    Optional<NewsPublisher> findByPublisherKey(String publisherKey);

    /**
     * 등록 도메인으로 매체를 찾는다. GDELT가 준 기사 주소의 host로 실제 매체를 가려내는 데 쓴다.
     *
     * <p>이 조회가 없으면 해외 기사를 임의의 국내 매체에 붙이게 되고, 그 매체의 허가 상태를 물려받아 회신하지 않은 매체의 기사가 공개될 수 있다(spec
     * FR-016).
     */
    Optional<NewsPublisher> findByRegisteredDomain(String registeredDomain);

    /** 수집 대상으로 활성화된 매체. 허가 상태와 무관하게 내부 수집은 진행한다. */
    List<NewsPublisher> findEnabled();

    /** 공개 노출 가능한 매체. 공개 조회·순위·내보내기가 이 목록으로 먼저 좁힌다(spec FR-016). */
    List<NewsPublisher> findPubliclyVisible();

    /** 공개 노출 가능한 매체가 하나라도 있는지. 없으면 공개 상태는 PREPARING이다(spec FR-014). */
    boolean existsPubliclyVisible();
}
