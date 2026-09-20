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

    /**
     * 새 계산을 남긴다.
     *
     * <p><b>갱신이 아니다.</b> 이미 저장된 링크를 넘기면 같은 단위로 다시 넣으려다 {@code UNIQUE (run_id, snapshot_key,
     * end_month, scenario)} 에 걸린다. 상태 전이(발행·이벤트 반영)는 한 행을 여러 경로가 쓰므로 컬럼 소유를 가른 메서드로 따로 둔다 — 004
     * {@code BacktestRunRepository} 와 같은 이유이며 그 경로를 만드는 것은 M5다.
     */
    RegimeLink create(RegimeLink link);
}
