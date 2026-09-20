package com.ssafy.thorp.myrecord.domain.vo;

/**
 * 경제환경 검색에 어디서 들어왔는가(spec 010 FR-029, 006 FR-012 후단).
 *
 * <p><b>결과의 성질이 아니라 요청의 성질이다.</b> 010의 {@code SnapshotKey}에 들어가지 않고 스냅샷에 저장되지도 않는다 — 경로가 달라도 같은
 * 계산이기 때문이다. 그것을 아는 것은 조회한 화면뿐이라 006의 기록 요청이 값을 받아 {@code regime_search_log}에 남긴다(research R15).
 *
 * <p>두 경로의 기록은 <b>별도 항목</b>이다. 한쪽이 다른 쪽을 대체하지 않는다(Story 3 시나리오 4).
 *
 * <p>값의 계약은 스키마의 {@code ck_regime_log_entry_path}다. 010은 이 표를 읽기만 하므로 enum을 두지 않았고, 행을 쓰는 006이 값을
 * 가진다.
 */
public enum EntryPath {

    /** 시장 흐름 화면에서 경제환경 검색으로 들어왔다. */
    MARKET_FLOW,

    /** 전략 결과 화면의 '경제환경이 비슷했던 과거 보기'로 들어왔다. */
    STRATEGY_RESULT
}
