package com.ssafy.thorp.regimelink.application.repository;

import com.ssafy.thorp.regimelink.domain.model.LinkFilter;
import com.ssafy.thorp.regimelink.domain.vo.FilterKey;
import java.util.Optional;

/**
 * 가용성 필터 저장소 (data-model §2).
 *
 * <p>같은 키는 같은 행이다. 사용자 식별자가 없는 캐시 성격의 표라 조회 경로에서 만들어져도 기록 부작용이 아니다.
 */
public interface RegimeLinkFilterRepository {

    Optional<LinkFilter> findByKey(FilterKey key);

    /**
     * 새 필터를 남긴다.
     *
     * <p>같은 키가 이미 있으면 그 행을 돌려준다 — 두 요청이 같은 묶음을 동시에 조회하면 키가 겹치므로, 충돌을 실패로 올리지 않는다.
     */
    LinkFilter save(LinkFilter filter);
}
