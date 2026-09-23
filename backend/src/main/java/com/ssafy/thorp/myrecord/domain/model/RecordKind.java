package com.ssafy.thorp.myrecord.domain.model;

/**
 * 기록 항목의 종류(spec 006 FR-004 필터 축).
 *
 * <p>세 종류가 한 표에 모이는 이유는 목록 질의가 하나여야 하기 때문이다(research R1). 종류마다 가리키는 원본이 다르고, 그 짝은 {@link
 * RecordRef}와 스키마의 {@code ck_my_record_ref_matches_kind}가 함께 지킨다.
 */
public enum RecordKind {

    /** 단일 분석 하나. {@code backtest_request} + {@code backtest_run}을 가리킨다. */
    ANALYSIS,

    /** 자동 탐색 묶음 하나. 후보가 만든 실행은 따로 항목이 되지 않고 이 묶음이 대표한다. */
    SEARCH_BUNDLE,

    /** 경제환경 탐색 하나. 진입 경로가 다르면 별도 항목이다(FR-012 후단). */
    REGIME_SEARCH
}
