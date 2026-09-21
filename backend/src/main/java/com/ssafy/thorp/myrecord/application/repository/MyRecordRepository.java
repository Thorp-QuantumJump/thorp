package com.ssafy.thorp.myrecord.application.repository;

import com.ssafy.thorp.myrecord.domain.model.MyRecord;
import com.ssafy.thorp.myrecord.domain.model.RecordKind;
import java.util.Optional;

/**
 * 개인 기록 항목 저장소(spec 006 data-model §1).
 *
 * <p><b>찾는 메서드가 모두 "살아 있는" 항목만 본다.</b> 삭제는 {@code deleted_at}을 채우는 것이고 지운 행은 남으므로, 조건을 빠뜨리면 지운 항목이
 * 목록·상한·재사용 판정에 되살아난다(research R6).
 *
 * <p>목록 질의({@code findPage})는 여기 없다. 상태를 종류별 원본에서 조인해 읽어야 해서 형태가 다르고, 006의 목록·상세 API와 함께 들어온다.
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

    /**
     * 그 사용자가 이 실행에 대해 <b>지금 보는 요청</b>. 공개 토큰과 상태를 여기서 읽는다.
     *
     * <p>기록은 요청을 저장하지 않는다. 한 사용자가 같은 실행에 요청을 여럿 가질 수 있고(동일 결과 재사용) 취소는 실행이 아니라 요청에 붙기 때문이다 — 하나를 골라
     * 저장해 두면 그 요청만 취소된 순간 목록이 '취소'를 보인다. 고르는 규칙은 <b>취소하지 않은 것 중 최신</b>이고, 전부 취소했으면 그중 최신이다(그때는
     * 사용자에게 취소가 맞다).
     *
     * <p><b>단건 요청만 본다.</b> 동일 버전 키에 실행 유형이 없어 같은 사용자의 탐색 후보도 같은 실행에 합류할 수 있는데, 항목을 만드는 쪽은 단건만
     * 만든다(FR-011: 후보는 묶음 항목이 대표한다). 유형을 가리지 않으면 단건 기록이 탐색 후보 요청의 상태를 따라간다.
     *
     * @return 그 사용자의 단건 요청이 하나도 없으면 빈 값. 접수가 항목을 만들므로 정상 경로에서는 비지 않는다
     */
    Optional<Long> findViewableRequestId(long ownerId, long runId);

    /** 탐색 묶음 저장 상한 판정이 세는 경로(FR-018, research R11). 지운 항목은 세지 않는다. */
    long countActiveByOwnerAndKind(long ownerId, RecordKind kind);
}
