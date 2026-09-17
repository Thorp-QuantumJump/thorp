package com.ssafy.thorp.regime.application.repository;

import com.ssafy.thorp.regime.domain.model.RegimeSnapshot;
import com.ssafy.thorp.regime.domain.vo.SearchMode;
import java.time.YearMonth;
import java.util.Optional;

/**
 * 사전 계산 결과를 읽는다. <b>저장 메서드를 두지 않는다</b> — 값을 넣는 것은 {@code data/} 배치뿐이다.
 *
 * <p>쓰기 경로를 여기에 열어 두면 언젠가 요청 처리 중에 결과를 만들어 넣게 되고, 그 순간 SC-002a("요청 처리 중 계산 0건")가 구조가 아니라 약속이 된다.
 */
public interface RegimeSnapshotRepository {

    /** 그 (모드·분야·기준월)의 <b>가장 큰 revision</b>. 보통 조회가 읽는 행이다(spec FR-029). */
    Optional<RegimeSnapshot> findLatest(SearchMode mode, String fieldId, YearMonth baselineMonth);

    /** 식별 키로 지목한 행. 006 기록이 고정한 항목을 그대로 연다(계약 §2). */
    Optional<RegimeSnapshot> findByKey(String snapshotKey);
}
