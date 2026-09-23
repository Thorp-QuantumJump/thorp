package com.ssafy.thorp.myrecord.application.result;

import com.ssafy.thorp.myrecord.application.command.MyRecordListQuery;
import com.ssafy.thorp.myrecord.domain.model.RecordKind;
import com.ssafy.thorp.myrecord.domain.model.RecordStatus;
import com.ssafy.thorp.myrecord.domain.vo.AnalysisPeriod;
import com.ssafy.thorp.myrecord.domain.vo.EntryPath;
import com.ssafy.thorp.regime.domain.vo.BaselineOrigin;
import com.ssafy.thorp.regime.domain.vo.SnapshotKey;
import java.time.Instant;
import java.util.List;

/**
 * 목록·상세가 보는 항목 한 줄(spec 006 contracts "GET /my-records" 응답 항목).
 *
 * <p>{@code my_record} 행에 종류별 원본을 조인해 읽은 값이다. 상태·사유·결과 토큰은 저장된 것이 아니라 <b>읽을 때 원본에서
 * 파생</b>한다(data-model §1 "상태는 컬럼이 아니다"). 성과 수치는 없다 — 목록에 수익을 나란히 보이면 정렬이 없어도 순위 화면이 된다(원칙 III).
 *
 * @param id DB PK. 응답에 나가지 않고 커서와 열람 표시에만 쓴다
 * @param statusReason 실패·취소·부분 완료·계산 불가의 사유 코드. 낮은 수익과 섞지 않는다(FR-014)
 * @param resultToken {@code ANALYSIS}에만 있다. '다시 보기' 링크의 열쇠(research R3)
 * @param searchId {@code SEARCH_BUNDLE}에만 있다
 * @param regime {@code REGIME_SEARCH}에만 있다
 * @param analysisRunId {@code ANALYSIS}에만 있다. 저장 당시 실행 조건({@code reopenPayload})을 그 실행에서 읽는다 — 응답에
 *     나가지 않는다(PK 는 노출하지 않는다)
 */
public record MyRecordView(
        long id,
        String recordId,
        RecordKind kind,
        String name,
        List<String> sectorCodes,
        List<String> strategyIds,
        AnalysisPeriod period,
        Instant savedAt,
        Instant lastViewedAt,
        RecordStatus status,
        String statusReason,
        String resultToken,
        String searchId,
        Regime regime,
        Long analysisRunId,
        boolean isCopy) {

    /**
     * 경제환경 항목의 검색 정보. {@code regime_search_log} 행에서 읽는다.
     *
     * @param key 010 스냅샷 식별 키의 사본. 모드·분야·기준월은 여기서 꺼낸다 — 철회로 스냅샷이 사라져도 무엇을 봤는지는 남는다
     * @param withdrawn 010 FR-035 철회로 스냅샷 참조가 끊겼다. '다시 보기'가 열리지 않는다
     */
    public record Regime(
            SnapshotKey key,
            BaselineOrigin baselineOrigin,
            EntryPath entryPath,
            boolean withdrawn) {}

    /** 열람 표시를 반영한 사본. 상세가 표시와 같은 트랜잭션에서 돌아가므로 다시 읽지 않고 값만 맞춘다. */
    public MyRecordView viewedAt(Instant at) {
        return new MyRecordView(
                id,
                recordId,
                kind,
                name,
                sectorCodes,
                strategyIds,
                period,
                savedAt,
                at,
                status,
                statusReason,
                resultToken,
                searchId,
                regime,
                analysisRunId,
                isCopy);
    }

    /** 이 줄 뒤에서 다음 쪽을 시작하는 자리. */
    public MyRecordListQuery.Cursor cursor() {
        return new MyRecordListQuery.Cursor(savedAt, name, id);
    }
}
