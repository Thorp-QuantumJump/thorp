package com.ssafy.thorp.regime.application.repository;

/**
 * 검색 기록을 읽는다 — <b>철회 여부를 가려내는 데만 쓴다</b> (spec FR-035).
 *
 * <p>기록을 만드는 것은 006·014의 몫이고 010은 쓰지 않는다. 사용자 식별자가 있는 표라 읽는 범위도 최소로 둔다.
 */
public interface RegimeSearchLogRepository {

    /**
     * 그 식별 키를 가리키던 기록의 참조가 끊겼는가 = <b>철회되었는가</b>.
     *
     * <p>정리(FR-029)와 철회(FR-035)를 이 신호 하나로 가른다. 정리는 <b>기록이 가리키는 항목을 남기므로</b>, 기록이 있는데 참조가 NULL인 것은
     * 철회뿐이다(data-model §7). 그래서 "정리되어 없다"와 "철회되어 없다"를 응답에서 구분할 수 있다.
     */
    boolean isWithdrawn(String snapshotKey);
}
