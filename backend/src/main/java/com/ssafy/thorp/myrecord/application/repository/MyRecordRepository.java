package com.ssafy.thorp.myrecord.application.repository;

import com.ssafy.thorp.myrecord.application.command.MyRecordListQuery;
import com.ssafy.thorp.myrecord.application.result.MyRecordPage;
import com.ssafy.thorp.myrecord.application.result.MyRecordView;
import com.ssafy.thorp.myrecord.application.result.SourceVersions;
import com.ssafy.thorp.myrecord.domain.model.MyRecord;
import com.ssafy.thorp.myrecord.domain.model.RecordKind;
import java.time.Instant;
import java.util.Optional;

/**
 * 개인 기록 항목 저장소(spec 006 data-model §1).
 *
 * <p><b>찾는 메서드가 모두 "살아 있는" 항목만 본다.</b> 삭제는 {@code deleted_at}을 채우는 것이고 지운 행은 남으므로, 조건을 빠뜨리면 지운 항목이
 * 목록·상한·재사용 판정에 되살아난다(research R6).
 *
 * <p>목록·상세 읽기({@link #findPage}·{@link #findView})는 도메인 모델이 아니라 {@link MyRecordView}를 돌려준다. 상태·결과
 * 토큰을 종류별 원본에서 조인해 파생하는 값이라 {@code my_record} 한 표의 모델로는 담을 수 없다(data-model §1 "상태는 컬럼이 아니다").
 */
public interface MyRecordRepository {

    /**
     * 새 항목을 저장하고 외부 식별자를 부여한다.
     *
     * @throws IllegalArgumentException 이미 저장된 항목이면. 갱신 경로는 이름 변경·삭제가 따로 가진다
     */
    MyRecord save(MyRecord record);

    /** 지운 항목도 돌려준다 — 부른 쪽이 404와 "이미 지움"을 구분한다. */
    Optional<MyRecord> findByRecordId(String recordId);

    /**
     * 같은 사용자가 같은 <b>실행</b>을 가리키는 살아 있는 항목. 같은 조건 재분석이 새 항목을 만들지 않게 한다(FR-009).
     *
     * <p>요청({@code backtest_request})이 아니라 실행({@code backtest_run})으로 찾는 이유: 004의 동일 결과 재사용은 요청을 새로
     * 만들고 실행을 공유한다. 요청으로 찾으면 매번 없다고 답한다.
     *
     * <p>사본({@code is_copy})은 제외한다 — 같은 원본을 가리키는 다른 이름의 행이라 재사용 대상이 아니다.
     */
    Optional<MyRecord> findActiveByOwnerAndRun(long ownerId, long runId);

    /** 같은 사용자가 같은 탐색 묶음을 가리키는 살아 있는 항목. */
    Optional<MyRecord> findActiveByOwnerAndParamSearch(long ownerId, long paramSearchId);

    /** 같은 사용자가 같은 경제환경 로그 행을 가리키는 살아 있는 항목. 진입 경로가 다르면 로그 행이 달라 별도 항목이 된다. */
    Optional<MyRecord> findActiveByOwnerAndRegimeLog(long ownerId, long regimeLogId);

    /** 탐색 묶음 저장 상한 판정이 세는 경로(FR-018, research R11). 지운 항목은 세지 않는다. */
    long countActiveByOwnerAndKind(long ownerId, RecordKind kind);

    /**
     * 목록 한 쪽. 소유자의 살아 있는 항목만, 필터·정렬·커서는 {@link MyRecordListQuery}가 정한다.
     *
     * <p><b>성과 수치 컬럼을 select 에 넣지 않는다</b>(원칙 III).
     */
    MyRecordPage findPage(long ownerId, MyRecordListQuery query);

    /** 한 항목. 남의 것·지운 것·없는 것을 구분하지 않고 빈 값이다 — 부른 쪽이 한 가지 404 로 답한다(FR-020). */
    Optional<MyRecordView> findView(long ownerId, String recordId);

    /**
     * 원본이 남긴 참조 버전의 원문. 경제환경 항목은 스냅샷 키가 버전을 품고 있어 여기 오지 않는다.
     *
     * @param id {@code my_record} PK
     */
    Optional<SourceVersions> findSourceVersions(long id, RecordKind kind);

    /** '다시 보기'로 열었다. 보관 기간(PD-13)을 나중에 도입할 수 있게 시각만 남긴다 — <b>시간 기반 자동 삭제는 만들지 않는다.</b> */
    void markViewed(long id, Instant at);
}
