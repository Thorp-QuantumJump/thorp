package com.ssafy.thorp.paramsearch.application.port;

import java.util.Collection;
import java.util.Map;

/**
 * 후보·기준 실행의 성과를 읽는 경계(contracts/param-search-internal.md §2).
 *
 * <p>원본은 004의 결과 저장소이고 007은 <b>읽기만 한다</b>. 007이 성과를 따로 저장하지 않는 이유는 같은 숫자가 두 곳에 생기면 어느 쪽이 사실인지 흐려지기
 * 때문이다 — 후보는 {@code backtest_job_id}로 원본을 가리킨다.
 *
 * <p>포트로 감싸는 이유: 선정 계산이 004의 저장소 타입을 직접 알면 결과 저장 구조가 바뀔 때마다 선정이 흔들린다. 여기서 넘어오는 것은 숫자 셋뿐이다.
 */
public interface CandidatePerformancePort {

    /**
     * 실행 id 여러 건의 성과를 한 번에 읽는다.
     *
     * @return 결과가 있는 실행만 담는다. 실패·미완료 실행은 키 자체가 없다 — 0으로 채우지 않는다(FR-011)
     */
    Map<Long, CandidatePerformance> findByRunIds(Collection<Long> runIds);

    /**
     * 실행 id 여러 건의 목록용 뷰를 읽는다 — 상태와 요청 식별자가 성과와 함께 필요할 때 쓴다.
     *
     * <p>{@link #findByRunIds}와 갈라 두는 이유: 선정 계산은 숫자 셋만 쓰고 화면은 식별자·상태까지 쓴다. 선정이 쓰지 않는 값을 함께 읽으면 묶음마다
     * 후보 수만큼 헛일이 된다.
     *
     * @return 존재하는 실행만 담는다
     */
    Map<Long, CandidateRunView> findRunViews(Collection<Long> runIds);
}
