package com.ssafy.thorp.myrecord.domain.model;

/**
 * 기록 항목이 가리키는 원본(spec 006 data-model §1).
 *
 * <p>006은 계산 결과를 복제하지 않고 <b>가리킨다</b>(원칙 VIII). 종류마다 가리키는 대상이 달라 sealed 로 나눴고, 종류와 참조의 짝은 스키마의
 * {@code ck_my_record_ref_matches_kind}가 한 번 더 지킨다 — {@code ref_id} 한 컬럼으로 종류별 대상을 가리키면 어느 표도 그 값을
 * 보증하지 않는다.
 */
public sealed interface RecordRef {

    /** 이 참조가 뜻하는 종류. {@code kind} 컬럼은 여기서 나오므로 둘이 어긋날 수 없다. */
    RecordKind kind();

    /**
     * 단일 분석.
     *
     * @param requestId 이 사용자의 요청. 공개 토큰을 여기서 읽어 '다시 보기' 링크로 쓴다(research R3)
     * @param runId 공용 계산. 같은 조건 재분석 판정(FR-009)이 이 값으로 기존 항목을 찾는다 — 요청은 새로 생겨도 실행은 같다
     */
    record Analysis(long requestId, long runId) implements RecordRef {
        @Override
        public RecordKind kind() {
            return RecordKind.ANALYSIS;
        }
    }

    /** 자동 탐색 묶음. 후보가 만든 실행은 항목이 되지 않는다 — 이 묶음이 대표한다. */
    record SearchBundle(long paramSearchId) implements RecordRef {
        @Override
        public RecordKind kind() {
            return RecordKind.SEARCH_BUNDLE;
        }
    }

    /** 경제환경 탐색. 검색 경로·기준월 근거·스냅샷 참조는 {@code regime_search_log} 행에 있다. */
    record RegimeSearch(long logId) implements RecordRef {
        @Override
        public RecordKind kind() {
            return RecordKind.REGIME_SEARCH;
        }
    }
}
