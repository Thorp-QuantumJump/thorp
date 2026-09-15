package com.ssafy.thorp.paramsearch.application.repository;

import com.ssafy.thorp.paramsearch.domain.model.CandidateStatus;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import java.util.List;

/** 탐색 후보 저장소 포트. 구현은 {@code infrastructure.persistence}에 둔다. */
public interface SearchCandidateRepository {

    /**
     * 접수 시점에 후보 전량을 한 번에 저장한다.
     *
     * <p>건당 저장을 쓰지 않는 이유: 후보가 묶음당 수백~천 건이라 왕복이 그만큼 늘어난다. 엔티티의 {@code allocationSize = 50}과 {@code
     * jdbc.batch_size = 50}이 이 경로를 위한 설정이다.
     */
    List<SearchCandidate> saveAll(Long paramSearchId, List<SearchCandidate> candidates);

    /** 후보 하나의 진행 상태를 반영한다. 조합 자체(파라미터·그리드 좌표·순번)는 갱신하지 않는다. */
    SearchCandidate update(SearchCandidate candidate);

    List<SearchCandidate> findByParamSearchId(Long paramSearchId);

    List<SearchCandidate> findByParamSearchIdAndStatus(Long paramSearchId, CandidateStatus status);

    /** 발행 창이 다음에 올릴 후보를 순번대로 {@code limit}건만 가져온다. */
    List<SearchCandidate> findNextByStatus(Long paramSearchId, CandidateStatus status, int limit);

    /**
     * 최근에 끝난 후보부터 {@code limit}건. 연속 실패 한도 판정에 쓴다(FR-005).
     *
     * <p>순번이 아니라 <b>끝난 순서</b>다 — 창 안에서 먼저 발행된 후보가 늦게 끝나는 일이 흔해서, 순번으로 세면 "줄줄이 실패했다"를 잘못 읽는다.
     */
    List<SearchCandidate> findRecentlyFinished(Long paramSearchId, int limit);

    long countByStatus(Long paramSearchId, CandidateStatus status);

    /**
     * 결과 이벤트가 도착했을 때 어느 후보의 것인지 찾는다.
     *
     * <p><b>실행 하나에 후보가 여럿 걸릴 수 있다.</b> 004가 같은 조건의 요청을 살아 있는 실행에 합류시키거나 완료 결과를 재사용하기 때문이다(spec 004
     * R-02). 같은 조건을 두 번 접수하면 두 묶음의 후보가 같은 실행 행을 가리키므로, 걸린 후보를 <b>전부</b> 돌려주지 않으면 한쪽 묶음이 영영 끝나지 않는다.
     */
    List<SearchCandidate> findAllByBacktestJobId(Long backtestJobId);
}
