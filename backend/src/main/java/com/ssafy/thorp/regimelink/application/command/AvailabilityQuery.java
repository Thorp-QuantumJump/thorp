package com.ssafy.thorp.regimelink.application.command;

import com.ssafy.thorp.regimelink.domain.vo.EntryPath;
import java.time.YearMonth;

/**
 * 가용성 판정 조회의 입력 (contracts §1 요청 표).
 *
 * <p>검색을 가리키는 방법이 둘이다 — {@code snapshotKey}가 있으면 사용자가 본 revision을 그대로 열고, 없으면 (모드·분야·기준월)로 가장 최신
 * revision을 읽는다. 새 revision으로 조용히 바꾸지 않기 위해 화면은 한 번 고정한 키를 계속 보낸다.
 *
 * @param base 비교 기준 공개 토큰. 필수
 * @param compare 비교 대상 공개 토큰. 없으면 null이며 최대 하나다(Clarifications 09-17 Q1)
 */
public record AvailabilityQuery(
        String base,
        String compare,
        String snapshotKey,
        String mode,
        String field,
        YearMonth baselineMonth,
        EntryPath entryPath) {

    public boolean pinned() {
        return snapshotKey != null && !snapshotKey.isBlank();
    }
}
