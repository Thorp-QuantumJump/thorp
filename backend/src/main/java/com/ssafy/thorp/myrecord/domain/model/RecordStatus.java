package com.ssafy.thorp.myrecord.domain.model;

/**
 * 목록에 보이는 상태(spec 006 FR-004·FR-014).
 *
 * <p><b>이 값은 {@code my_record}에 저장되지 않는다.</b> 종류별 원본({@code backtest_run}·{@code
 * param_search}·{@code regime_search_snapshot})을 조인해 읽는다 — 워커 이벤트로 계속 바뀌는 값이라 복제하면 갱신 누락이 "실패한 분석이
 * 목록에서는 완료로 보이는" 모습으로 드러난다(원칙 VI, research R2).
 *
 * <p>뒤의 넷은 <b>010이 정한 값을 그대로 옮긴 것</b>이며 완료/실패로 바꿔 부르지 않는다(FR-004). 경제환경 검색의 '계산 불가'는 실패가 아니라 그 기준월에
 * 견줄 사례가 없었다는 사실이고, 그것을 FAILED로 표시하면 사용자가 서비스 장애로 읽는다.
 */
public enum RecordStatus {

    /** 계산이 끝났다. 목록의 기본 보기다. */
    COMPLETED,

    /** 아직 돌고 있다. */
    RUNNING,

    /** 실패했다. 사유는 원본의 실패 코드를 그대로 보인다 — 낮은 수익과 다른 자리에 표시한다(FR-014). */
    FAILED,

    /** 사용자가 취소했다. */
    CANCELLED,

    /** 탐색 묶음이 일부만 끝났다. 완료 범위를 함께 보인다. */
    PARTIAL,

    /** 010: 그 기준월의 척도를 말할 수 없어 계산하지 못했다. */
    NOT_COMPUTABLE,

    /** 010: 문턱을 넘은 사례가 없어 가장 가까운 것만 보인다. */
    NEAREST_ONLY,

    /** 010: 문턱을 적용해 사례를 골랐다. 경제환경 검색의 정상 완료에 해당한다. */
    THRESHOLD_APPLIED,

    /** 010 FR-035 철회로 스냅샷 참조가 끊겼다. '다시 보기'가 열리지 않는다. */
    WITHDRAWN
}
