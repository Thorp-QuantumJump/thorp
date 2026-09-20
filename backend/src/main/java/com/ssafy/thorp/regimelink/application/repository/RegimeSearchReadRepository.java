package com.ssafy.thorp.regimelink.application.repository;

import java.time.YearMonth;
import java.util.Optional;

/**
 * 010 검색 결과를 읽기 전용으로 가져온다 (research R8).
 *
 * <p><b>010 표를 011이 직접 매핑하지 않는다.</b> 구현은 010의 {@code RegimeSnapshotRepository}에 위임하고 011 모델로 옮기기만
 * 한다 — 같은 표를 두 도메인이 매핑하면 010이 스키마를 바꿀 때 011의 매핑이 조용히 어긋난다.
 *
 * <p>쓰기 메서드를 두지 않는다. 010 값을 채우는 것은 사전 계산 배치뿐이다.
 */
public interface RegimeSearchReadRepository {

    /** 그 (모드·분야·기준월)의 가장 큰 revision. 기준월이 없으면 010 규칙이 정한 기본 기준월을 쓴다. */
    Optional<RegimeSearchView> findLatest(String mode, String fieldId, YearMonth baselineMonth);

    /** 사용자가 본 revision을 그대로 연다. 새 revision으로 조용히 바꾸지 않는다(REST §1b). */
    Optional<RegimeSearchView> findByKey(String snapshotKey);
}
