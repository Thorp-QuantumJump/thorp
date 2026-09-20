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
     * 단일 분석. <b>실행만 가리킨다.</b>
     *
     * <p>요청({@code backtest_request})을 가리키지 않는 이유: 한 사용자가 같은 실행에 요청을 여럿 가질 수 있고(동일 결과 재사용) 취소는 실행이
     * 아니라 요청에 붙는다. 그중 하나를 골라 두면 그 요청만 취소된 순간 기록이 틀린 상태(취소·부분 결과)를 보이고, 어느 요청을 골라도 뒤에 오는 취소가 그것을 틀리게
     * 만들 수 있다. 공개 토큰과 상태는 읽을 때 (소유자·실행)로 요청을 골라 읽는다.
     *
     * @param runId 공용 계산. 같은 조건 재분석 판정(FR-009)도 이 값으로 기존 항목을 찾는다
     */
    record Analysis(long runId) implements RecordRef {
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
