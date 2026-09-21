package com.ssafy.thorp.regimelink.application.repository;

import com.ssafy.thorp.regimelink.domain.model.RegimeLinkUse;
import java.util.List;

/**
 * 누가 어느 요청으로 계산을 열었나 (data-model §4a).
 *
 * <p>{@code UNIQUE (link_search_id, link_id)}라 한 검색이 같은 계산을 두 번 열어도 행은 하나다.
 */
public interface RegimeLinkUseRepository {

    List<RegimeLinkUse> findByLinkSearchId(Long linkSearchId);

    List<RegimeLinkUse> findByLinkId(Long linkId);

    RegimeLinkUse save(RegimeLinkUse use);
}
