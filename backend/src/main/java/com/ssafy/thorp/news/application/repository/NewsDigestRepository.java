package com.ssafy.thorp.news.application.repository;

import com.ssafy.thorp.news.domain.model.NewsDigest;
import com.ssafy.thorp.news.domain.vo.NewsDigestId;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 묶음 저장소 경계.
 *
 * <p>여기서 읽는 묶음은 전체 멤버를 담는다. 공개 응답에 쓸 대표·요약·매체 수는 {@code NewsDigest#projectFor}가 허가된 기사만으로 조회 시점에 다시
 * 계산한다. 저장소는 허가 변경으로 저장된 값을 덮어쓰지 않는다(spec FR-016).
 */
public interface NewsDigestRepository {

    NewsDigest save(NewsDigest digest);

    Optional<NewsDigest> findByDigestId(NewsDigestId digestId);

    /** 현재 뉴스 후보 묶음. 허가 필터와 정렬·개수 제한은 조회 서비스가 적용한다. */
    List<NewsDigest> findRecent(Instant since);

    /** 그 섹터에 연결된 묶음. 섹터 식별자는 spec 001이 소유하는 외부 값이다(spec FR-005). */
    List<NewsDigest> findRecentBySector(Instant since, String sectorId);

    /**
     * 섹터 여럿 가운데 하나 이상에 연결된 현재 뉴스 후보 묶음(spec FR-001 ③, 2026-10-02). 한 묶음은 한 번만 들어간다.
     *
     * <p>기본 구현은 섹터마다 {@link #findRecentBySector}를 불러 합친다. 결과는 같지만 섹터 수만큼 질의가 나가므로 영속 구현은 한 번의 질의로
     * 바꿔 쓴다. 정렬과 개수 제한은 조회 서비스가 주요도 규칙으로 한다.
     */
    default List<NewsDigest> findRecentInSectors(Instant since, Collection<String> sectorIds) {
        Map<String, NewsDigest> byDigestId = new LinkedHashMap<>();
        for (String sectorId : sectorIds) {
            for (NewsDigest digest : findRecentBySector(since, sectorId)) {
                byDigestId.putIfAbsent(digest.getDigestId().value(), digest);
            }
        }
        return List.copyOf(byDigestId.values());
    }

    /** 발행 시각이 확인됐고 구간 안인 기사로 이뤄진 묶음(spec FR-006). */
    List<NewsDigest> findVerifiedBetween(Instant from, Instant to, String sectorId);

    /** 그 회차가 건드린 기사가 속한 묶음. 묶음 재생성 범위를 그 회차로 한정한다(spec FR-022). */
    List<NewsDigest> findByArticleIds(List<Long> articleIds);

    /**
     * 이 기사들이 속한 묶음을 지운다. 묶음을 다시 만들기 전에 부른다.
     *
     * <p>묶음 식별자는 대표 기사에서 유도되므로 대표가 바뀌면 새 식별자가 생긴다. 옛 묶음을 지우지 않으면 같은 사건이 두 묶음으로 남는다(spec SC-002).
     */
    void deleteByArticleIds(java.util.List<Long> articleIds);
}
