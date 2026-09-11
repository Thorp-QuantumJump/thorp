package com.ssafy.thorp.paramsearch.application.port;

import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;

/**
 * 후보 1건과 기준 1건을 기존 백테스트 실행 경로로 보낸다(contracts/param-search-internal.md §2).
 *
 * <p>007은 작업 메시지를 직접 만들지 않는다 — 004의 접수 유스케이스를 부른다. 메시지 계약과 워밍업·비용·재사용 판정은 004의 것이고, 007이 그것을 흉내 내면
 * 같은 규칙이 두 곳에 생긴다.
 *
 * <p>두 메서드 모두 <b>묶음 스냅샷만 보고</b> 조립한다. 발행 시점의 자료·비용 버전을 다시 읽지 않는다(FR-012).
 */
public interface CandidateJobLauncher {

    /** 후보 하나를 접수하고 실행 행의 id를 돌려준다. 후보는 그 id로 자기 결과를 찾는다. */
    long launchCandidate(ParamSearchSnapshot snapshot, SearchCandidate candidate);

    /**
     * 손실 제약의 기준 1건을 후보와 같은 조건으로 접수한다(FR-013d).
     *
     * <p>기준 전략과 그 파라미터는 스냅샷이 들고 있다 — 어느 전략이 기준인지 이 코드는 알지 못한다(FR-025). 지정이 없으면 호출자가 부르지 않는다.
     */
    long launchBaseline(ParamSearchSnapshot snapshot);
}
