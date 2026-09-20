package com.ssafy.thorp.regimelink.application.repository;

import com.ssafy.thorp.regimelink.domain.model.RegimeLink;
import com.ssafy.thorp.regimelink.domain.vo.CaseRef;
import com.ssafy.thorp.regimelink.domain.vo.LinkId;
import com.ssafy.thorp.regimelink.domain.vo.Scenario;
import java.util.List;
import java.util.Optional;

/**
 * (실행 × 사례 × 시나리오) 계산 저장소 (data-model §4).
 *
 * <p>{@code UNIQUE (run_id, snapshot_key, end_month, scenario)}가 "같은 단위는 다시 계산하지 않는다"(FR-010)를 스키마로
 * 강제하므로, 만들기 전에 {@link #findUnit}으로 먼저 찾는다.
 */
public interface RegimeLinkRepository {

    Optional<RegimeLink> findByLinkId(LinkId linkId);

    /** 이미 그 단위의 계산이 있는가. 있으면 상태와 결과를 그대로 쓴다. */
    Optional<RegimeLink> findUnit(Long runId, CaseRef caseRef, Scenario scenario);

    List<RegimeLink> findByRunId(Long runId);

    RegimeLink save(RegimeLink link);
}
