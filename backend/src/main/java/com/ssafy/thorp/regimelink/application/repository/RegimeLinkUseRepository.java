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

    /**
     * 그 실행에서 비롯된 이용 기록.
     *
     * <p>현금 시작·전체 기간 재계산 링크는 {@code run_id}가 파생 실행이라 원래 실행으로는 찾히지 않는다. "어느 전략의 결과인가"를 정하는 것은 {@code
     * origin_run_id}다(data-model §4a).
     */
    List<RegimeLinkUse> findByOriginRunId(Long originRunId);

    RegimeLinkUse save(RegimeLinkUse use);
}
