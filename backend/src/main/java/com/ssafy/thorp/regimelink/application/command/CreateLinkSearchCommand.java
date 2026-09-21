package com.ssafy.thorp.regimelink.application.command;

import com.ssafy.thorp.regimelink.domain.vo.EntryPath;
import com.ssafy.thorp.regimelink.domain.vo.LinkSearchId;

/**
 * 검색 기록 생성의 입력 (contracts §1b).
 *
 * @param clientRequestId 요청자별 멱등 키. 같은 키·같은 본문은 기존 기록을 돌려주고, 같은 키·다른 본문은 400 이다
 * @param snapshotKey 사용자가 본 010 revision. 새 revision 으로 조용히 바꾸지 않는다
 * @param previousLinkSearchId 같은 흐름의 직전 기록. 없으면 null 이며 비교 묶음 변경 알림도 없다
 */
public record CreateLinkSearchCommand(
        long ownerUserId,
        String clientRequestId,
        String snapshotKey,
        String base,
        String compare,
        EntryPath entryPath,
        boolean fullSearchShown,
        LinkSearchId previousLinkSearchId) {}
